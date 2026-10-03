#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <psapi.h>
#include <tlhelp32.h>
#include <bcrypt.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>
#include "detours.h"

// Diagnostics plus the narrowly verified null player-pool dispatch guard.
namespace {
constexpr char Build[]="solo-diagnostics-20261001-v6-startup-cpp";
constexpr unsigned Capacity=128;
enum Kind : DWORD { Receive=1, Send=2, Connect=3, Close=4, Shutdown=5,
    AsyncReceive=6, AsyncSend=7, ProcessExit=8, ProcessTerminate=9, Fault=10, UiStall=11,
    PlayerPacket=12, NullPlayerPool=13, CppException=14 };
struct Event { LONG sequence; DWORD tick,thread,kind,a,b,c; };
struct Slot { volatile LONG busy; Event event; };
Slot ring[Capacity]{};
volatile LONG sequence=0, lost=0, ready=0, faultBusy=0, faultCount=0, stopping=0;
volatile LONG rxBytes=0, txBytes=0, rxCalls=0, txCalls=0;
volatile LONG poolGuardReady=0, rejectedPoolPackets=0;
volatile LONG lifecycleReady=0,lifecycleBusy=0,lifecycleSequence=0,firstNull=0;
bool gameHost=false;
bool proxyIsV5=false;HMODULE verifiedProxy=nullptr;
volatile LONG nativeErrorFixReady=0;
HANDLE lifecycleFile=INVALID_HANDLE_VALUE,firstNullFile=INVALID_HANDLE_VALUE;
struct LifecycleRecord { DWORD magic,version,sequence,kind,thread,tick;FILETIME utc;DWORD object,stage,players,npcs,count;void* frames[16]; };
static_assert(sizeof(LifecycleRecord)==116,"Lifecycle record ABI");
using Dispose=void(__thiscall*)(void*);
using Clear=void(__cdecl*)();
using SetStage=void(__cdecl*)(void*,void*);
Dispose realPlayerDispose=nullptr,realNpcDispose=nullptr;
Clear realPlayerClear=nullptr,realNpcClear=nullptr;
SetStage realSetStage=nullptr;
using PlayerDispatch=void(__thiscall*)(void*,DWORD,void*);
PlayerDispatch realPlayerDispatch=nullptr;
constexpr BYTE PoolEntryBytes[]={0x55,0x8b,0xec,0x8b,0x45,0x08,0x8b,0xd0,0x81,0xea,0xa0,0,0,0};
constexpr BYTE FieldCallBytes[]={0xff,0x75,0x0c,0x8b,0x0d,0xa8,0xbf,0xbe,0,0x50,0xe8,0x50,0x0b,0x44,0};
void Line(const char* message);
void HookError(const char* phase,LONG error){char text[200];std::snprintf(text,sizeof(text),"hook_error phase=%s code=%ld",phase,error);Line(text);}
struct FaultRecord {
    DWORD magic,version,pid,thread,tick,kind; FILETIME utc;
    EXCEPTION_RECORD exception; CONTEXT context; DWORD count;
    Event events[Capacity];
};
HANDLE critical=INVALID_HANDLE_VALUE, wake=nullptr, captured=nullptr;
HANDLE faultCodeFile=INVALID_HANDLE_VALUE;
struct FaultCodeRecord { DWORD magic,version,sequence,eip,allocation,protection,begin,length;BYTE code[224]; };
static_assert(sizeof(FaultCodeRecord)==256,"Bounded instruction capture ABI");
FaultCodeRecord faultCodeRecord{};
HMODULE selfModule=nullptr;
wchar_t root[MAX_PATH]{},logPath[MAX_PATH]{},faultPath[MAX_PATH]{},dumpTool[MAX_PATH]{};
char session[80]{};
FaultRecord faultRecord{}; // One bounded preallocated record; no heap in VEH.
volatile LONG dumpRequested=0,dumps=0;
volatile LONG firstCppCaptured=0;
auto realRecv=&recv; auto realSend=&send; auto realConnect=&connect;
auto realClose=&closesocket; auto realShutdown=&shutdown;
auto realWSARecv=&WSARecv; auto realWSASend=&WSASend;
auto realExit=&ExitProcess; auto realTerminate=&TerminateProcess;

void Record(DWORD kind,DWORD a=0,DWORD b=0,DWORD c=0) {
    const LONG n=InterlockedIncrement(&sequence);
    Slot& s=ring[static_cast<unsigned>(n)%Capacity];
    if(InterlockedCompareExchange(&s.busy,1,0)!=0){InterlockedIncrement(&lost);return;}
    s.event={n,GetTickCount(),GetCurrentThreadId(),kind,a,b,c};
    InterlockedExchange(&s.busy,0);
}
DWORD Snapshot(Event* output) {
    DWORD count=0;
    for(auto& s:ring) if(InterlockedCompareExchange(&s.busy,1,0)==0){
        if(s.event.sequence)output[count++]=s.event;
        InterlockedExchange(&s.busy,0);
    }
    return count;
}
bool Readable(const void* address,size_t length){
    MEMORY_BASIC_INFORMATION info{};
    if(!address || VirtualQuery(address,&info,sizeof(info))!=sizeof(info))return false;
    uintptr_t begin=reinterpret_cast<uintptr_t>(address),region=reinterpret_cast<uintptr_t>(info.BaseAddress);
    return info.State==MEM_COMMIT && !(info.Protect&(PAGE_NOACCESS|PAGE_GUARD)) && begin>=region && length<=info.RegionSize-(begin-region);
}
__declspec(noinline) void Lifecycle(DWORD kind,void* object=nullptr,bool first=false){
    DWORD saved=GetLastError();
    HANDLE file=first?firstNullFile:lifecycleFile;
    // First-null has its own handle/file-position and cannot lose the record to
    // a concurrent destructor occupying the ordinary trace writer.
    if(file==INVALID_HANDLE_VALUE || (!first && InterlockedCompareExchange(&lifecycleBusy,1,0))){SetLastError(saved);return;}
    LifecycleRecord r{};r.magic=0x53444c31;r.version=1;r.sequence=InterlockedIncrement(&lifecycleSequence);r.kind=kind;r.thread=GetCurrentThreadId();r.tick=GetTickCount();GetSystemTimeAsFileTime(&r.utc);r.object=reinterpret_cast<DWORD>(object);
    if(gameHost){r.stage=*reinterpret_cast<DWORD*>(0xbeded4);r.players=*reinterpret_cast<DWORD*>(0xbebfa8);r.npcs=*reinterpret_cast<DWORD*>(0xbed780);}
    r.count=CaptureStackBackTrace(1,16,r.frames,nullptr);
    // Freeze the preceding 64 lifecycle records as soon as the first missing pool
    // is observed. Later teardown records use a separate bounded 64-slot area.
    DWORD slot=first?64:((InterlockedCompareExchange(&firstNull,0,0)?65:0)+(r.sequence-1)%64);
    LARGE_INTEGER at{};at.QuadPart=slot*sizeof(r);DWORD written=0;
    if(SetFilePointerEx(file,at,nullptr,FILE_BEGIN))WriteFile(file,&r,sizeof(r),&written,nullptr);
    FlushFileBuffers(file);if(!first)InterlockedExchange(&lifecycleBusy,0);SetLastError(saved);
}
void __fastcall PlayerDisposeHook(void* p,void*){Lifecycle(1,p);realPlayerDispose(p);Lifecycle(2,p);}
void __fastcall NpcDisposeHook(void* p,void*){Lifecycle(3,p);realNpcDispose(p);Lifecycle(4,p);}
void __cdecl PlayerClearHook(){Lifecycle(5);realPlayerClear();Lifecycle(6);}
void __cdecl NpcClearHook(){Lifecycle(7);realNpcClear();Lifecycle(8);}
void __cdecl SetStageHook(void* p,void* parameter){Lifecycle(9,p);realSetStage(p,parameter);Lifecycle(10,p);}
void __fastcall PlayerDispatchHook(void* pool,void*,DWORD opcode,void* packet){
    DWORD length=0xffffffff;
    if(Readable(packet,24)){WORD size=0;std::memcpy(&size,static_cast<BYTE*>(packet)+16,sizeof(size));length=size;}
    Record(PlayerPacket,opcode,length,reinterpret_cast<DWORD>(pool));
    if(!pool){if(!InterlockedCompareExchange(&firstNull,1,0))Lifecycle(11,nullptr,true);InterlockedIncrement(&rejectedPoolPackets);Record(NullPlayerPool,opcode,length);return;}
    realPlayerDispatch(pool,opcode,packet);
}
bool UpdateThreads(std::vector<HANDLE>& threads){
    LONG error=DetourUpdateThread(GetCurrentThread());if(error!=NO_ERROR){HookError("current_thread",error);return false;}
    HANDLE snapshot=CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD,0);
    if(snapshot==INVALID_HANDLE_VALUE){HookError("thread_snapshot",GetLastError());return false;}
    bool okay=true;THREADENTRY32 entry{};entry.dwSize=sizeof(entry);
    if(!Thread32First(snapshot,&entry)){HookError("thread_first",GetLastError());okay=false;}
    else do{
        if(entry.th32OwnerProcessID!=GetCurrentProcessId() || entry.th32ThreadID==GetCurrentThreadId())continue;
        HANDLE thread=OpenThread(THREAD_SUSPEND_RESUME|THREAD_GET_CONTEXT|THREAD_SET_CONTEXT|THREAD_QUERY_INFORMATION,FALSE,entry.th32ThreadID);
        if(!thread){DWORD code=GetLastError();if(code==ERROR_INVALID_PARAMETER)continue;HookError("open_thread",code);okay=false;break;}
        threads.push_back(thread);error=DetourUpdateThread(thread);
        if(error!=NO_ERROR){HookError("update_thread",error);okay=false;break;}
    }while(Thread32Next(snapshot,&entry));
    CloseHandle(snapshot);return okay;
}
bool SetPoolHook(PlayerDispatch address,bool enable){
    LONG error=DetourTransactionBegin();if(error!=NO_ERROR){HookError("pool_begin",error);return false;}
    std::vector<HANDLE> threads;bool okay=UpdateThreads(threads);
    if(enable)realPlayerDispatch=address;
    if(okay){error=enable?DetourAttach(reinterpret_cast<PVOID*>(&realPlayerDispatch),PlayerDispatchHook):DetourDetach(reinterpret_cast<PVOID*>(&realPlayerDispatch),PlayerDispatchHook);if(error!=NO_ERROR){HookError("pool_attach_detach",error);okay=false;}}
    if(okay){error=DetourTransactionCommit();okay=error==NO_ERROR;if(!okay)HookError("pool_commit",error);}else DetourTransactionAbort();
    for(HANDLE thread:threads)CloseHandle(thread);
    return okay;
}
bool InstallPoolGuard(){
    const void* target=reinterpret_cast<void*>(0x0097208c);const void* caller=reinterpret_cast<void*>(0x0053152d);
    if(!Readable(target,sizeof(PoolEntryBytes)) || !Readable(caller,sizeof(FieldCallBytes)))return false;
    if(std::memcmp(target,PoolEntryBytes,sizeof(PoolEntryBytes)) || std::memcmp(caller,FieldCallBytes,sizeof(FieldCallBytes)))return false;
    if(!SetPoolHook(reinterpret_cast<PlayerDispatch>(const_cast<void*>(target)),true))return false;
    InterlockedExchange(&poolGuardReady,1);Line("player_pool_guard ACTIVE entry=0097208C caller=0053152D exact_bytes=verified; null pool packets discarded; valid pool forwarded; decoded opcode/length only");return true;
}
bool SetLifecycleHooks(bool enable){
    LONG error=DetourTransactionBegin();if(error!=NO_ERROR)return false;
    std::vector<HANDLE> threads;bool okay=UpdateThreads(threads);
#define TRACE_ATTACH(original,hook) if(okay){error=enable?DetourAttach(reinterpret_cast<PVOID*>(&original),hook):DetourDetach(reinterpret_cast<PVOID*>(&original),hook);if(error!=NO_ERROR){HookError(#hook,error);okay=false;}}
    TRACE_ATTACH(realPlayerDispose,PlayerDisposeHook);TRACE_ATTACH(realNpcDispose,NpcDisposeHook);
    TRACE_ATTACH(realPlayerClear,PlayerClearHook);TRACE_ATTACH(realNpcClear,NpcClearHook);TRACE_ATTACH(realSetStage,SetStageHook);
#undef TRACE_ATTACH
    if(okay){error=DetourTransactionCommit();okay=error==NO_ERROR;if(!okay)HookError("lifecycle_commit",error);}else DetourTransactionAbort();
    for(HANDLE thread:threads)CloseHandle(thread);
    return okay;
}
bool InstallLifecycle(){
    struct Check{DWORD at;BYTE bytes[8];size_t size;};
    const Check checks[]={
        {0x971573,{0xb8,0xf1,0xfc,0xad,0,0xe8},6},
        {0x6d926f,{0xb8,0x3e,0xa2,0xaa,0,0xe8},6},
        {0x9732f6,{0x83,0x25,0xa8,0xbf,0xbe,0,0,0xc3},8},
        {0x6d9aba,{0x83,0x25,0x80,0xd7,0xbe,0,0,0xc3},8},
        {0x777347,{0xb8,0x58,0x61,0xab,0,0xe8},6}};
    for(const auto& check:checks){const void* p=reinterpret_cast<void*>(check.at);if(!Readable(p,check.size)||std::memcmp(p,check.bytes,check.size))return false;}
    realPlayerDispose=reinterpret_cast<Dispose>(0x971573);realNpcDispose=reinterpret_cast<Dispose>(0x6d926f);
    realPlayerClear=reinterpret_cast<Clear>(0x9732f6);realNpcClear=reinterpret_cast<Clear>(0x6d9aba);realSetStage=reinterpret_cast<SetStage>(0x777347);
    bool okay=SetLifecycleHooks(true);
    if(okay){InterlockedExchange(&lifecycleReady,1);Lifecycle(12);Line("lifecycle_trace ACTIVE: verified stage setter and player/NPC destructor/clear entries; observation only; frozen 64 prior records, first-null stack, 64 later records");}
    return okay;
}
bool InstallNativeErrorFix(){
    if(!proxyIsV5 || !verifiedProxy)return false;
    BYTE* entry=reinterpret_cast<BYTE*>(verifiedProxy)+0xc0cc;
    constexpr BYTE expected[]={0x8b,0x77,0x34,0x85,0xf6,0x0f,0x89,0xec,0,0,0};
    if(!Readable(entry,sizeof(expected))||memcmp(entry,expected,sizeof(expected)))return false;
    if(DetourTransactionBegin()!=NO_ERROR)return false;
    std::vector<HANDLE> threads;bool okay=UpdateThreads(threads);
    DWORD protect=0;
    if(okay && !memcmp(entry,expected,sizeof(expected)) && VirtualProtect(entry+6,1,PAGE_EXECUTE_READWRITE,&protect)){
        entry[6]=0x84; // JNS -> JZ: zero alone means no pending native error.
        DWORD ignored=0;BOOL restored=VirtualProtect(entry+6,1,protect,&ignored);
        FlushInstructionCache(GetCurrentProcess(),entry,sizeof(expected));
        okay=restored!=FALSE;
    }else okay=false;
    // No detours queued: this transaction provides scoped thread suspension.
    DetourTransactionAbort();for(HANDLE thread:threads)CloseHandle(thread);
    if(okay){InterlockedExchange(&nativeErrorFixReady,1);Line("native_error_fix ACTIVE pinned_v5_sha256=A4DF286A RVA=C0D2 byte89->84; nonzero ZException handled before next update; original error5 throw captured");}
    return okay;
}
// Never follow exception-object pointers or inspect socket buffer contents.
void Critical(DWORD kind,EXCEPTION_POINTERS* pointers=nullptr,DWORD code=0) {
    DWORD saved=GetLastError();
    if(critical==INVALID_HANDLE_VALUE || InterlockedCompareExchange(&faultBusy,1,0)!=0)return;
    const LONG count=InterlockedIncrement(&faultCount);
    {
        ZeroMemory(&faultRecord,sizeof(faultRecord));
        faultRecord.magic=0x53444331;faultRecord.version=1;
        faultRecord.pid=GetCurrentProcessId();faultRecord.thread=GetCurrentThreadId();
        faultRecord.tick=GetTickCount();faultRecord.kind=kind;
        GetSystemTimeAsFileTime(&faultRecord.utc);
        if(pointers){faultRecord.exception=*pointers->ExceptionRecord;faultRecord.exception.ExceptionRecord=nullptr;faultRecord.context=*pointers->ContextRecord;}
        else faultRecord.exception.ExceptionCode=code;
        faultRecord.count=Snapshot(faultRecord.events);
        // Keep the latest 16 first-chance faults, with separate exit/detach slots.
        // Repeated handled faults cannot consume the termination evidence.
          DWORD slot=kind==100?17:((kind==ProcessExit || kind==ProcessTerminate)?16:(static_cast<DWORD>(count-1)%16));
          if(pointers && faultCodeFile!=INVALID_HANDLE_VALUE){
              ZeroMemory(&faultCodeRecord,sizeof(faultCodeRecord));
              faultCodeRecord.magic=0x53444332;faultCodeRecord.version=1;
              faultCodeRecord.sequence=count;faultCodeRecord.eip=pointers->ContextRecord->Eip;
              MEMORY_BASIC_INFORMATION codeRegion{};
              const DWORD eip=faultCodeRecord.eip;
              if(VirtualQuery(reinterpret_cast<void*>(eip),&codeRegion,sizeof(codeRegion))==sizeof(codeRegion)
                 && codeRegion.State==MEM_COMMIT && !(codeRegion.Protect&(PAGE_NOACCESS|PAGE_GUARD))
                 && (codeRegion.Protect&(PAGE_EXECUTE_READ|PAGE_EXECUTE_READWRITE|PAGE_EXECUTE_WRITECOPY))){
                  faultCodeRecord.allocation=reinterpret_cast<DWORD>(codeRegion.AllocationBase);
                  faultCodeRecord.protection=codeRegion.Protect;
                  const DWORD regionBegin=reinterpret_cast<DWORD>(codeRegion.BaseAddress);
                  const DWORD begin=eip-regionBegin>=32?eip-32:regionBegin;
                  const size_t available=codeRegion.RegionSize-(begin-regionBegin);
                  const DWORD length=static_cast<DWORD>(std::min(available,sizeof(faultCodeRecord.code)));
                  __try { memcpy(faultCodeRecord.code,reinterpret_cast<void*>(begin),length);
                      faultCodeRecord.begin=begin;faultCodeRecord.length=length; }
                  __except(EXCEPTION_EXECUTE_HANDLER) { faultCodeRecord.length=0; }
              }
              LARGE_INTEGER codePosition{};codePosition.QuadPart=static_cast<LONGLONG>(slot)*sizeof(faultCodeRecord);
              DWORD codeWritten=0;
              if(SetFilePointerEx(faultCodeFile,codePosition,nullptr,FILE_BEGIN))
                  WriteFile(faultCodeFile,&faultCodeRecord,sizeof(faultCodeRecord),&codeWritten,nullptr);
              FlushFileBuffers(faultCodeFile);
          }
        LARGE_INTEGER position{};position.QuadPart=static_cast<LONGLONG>(slot)*sizeof(faultRecord);
        DWORD written=0;if(SetFilePointerEx(critical,position,nullptr,FILE_BEGIN))WriteFile(critical,&faultRecord,sizeof(faultRecord),&written,nullptr);
        FlushFileBuffers(critical);
    }
    InterlockedExchange(&faultBusy,0);
    SetLastError(saved);
}
void RequestDump() {
    if(!wake || InterlockedCompareExchange(&dumps,0,0)>=2)return;
    if(InterlockedCompareExchange(&dumpRequested,1,0)==0){ResetEvent(captured);SetEvent(wake);WaitForSingleObject(captured,1200);}
}
LONG CALLBACK OnException(EXCEPTION_POINTERS* p) {
    DWORD code=p->ExceptionRecord->ExceptionCode;
    bool originalCaptured=false;
    // Read only the four-byte code of the exact known native ZException type.
    // Preserve its original throw stack before WndProc's catch destroys pools.
    if(gameHost && code==0xE06D7363 && p->ExceptionRecord->NumberParameters==3 && p->ExceptionRecord->ExceptionInformation[2]==0x00b44ee0){
        const void* object=reinterpret_cast<void*>(p->ExceptionRecord->ExceptionInformation[1]);
        if(Readable(object,4)){
            DWORD nativeCode=0;std::memcpy(&nativeCode,object,4);
            if(nativeCode==5){Record(Fault,code,5);Lifecycle(13);Critical(Fault,p);RequestDump();originalCaptured=true;}
        }
    }
    if(code==0xE06D7363){
        Record(Fault,code,reinterpret_cast<DWORD>(p->ExceptionRecord->ExceptionAddress),p->ExceptionRecord->NumberParameters);
        // Startup errors can be caught by the client's outer handler and close
        // the app without an AV. Retain the FIRST C++ throw's native context and
        // type/object addresses, even when it is handled. Do not dereference an
        // unknown exception object or count this as an unhandled crash.
        // One snapshot and the existing two-dump ceiling bound cost/retention.
        if(InterlockedCompareExchange(&firstCppCaptured,1,0)==0 && !originalCaptured){
            Critical(CppException,p);RequestDump();
        }
    }
    if(code==EXCEPTION_ACCESS_VIOLATION || code==EXCEPTION_IN_PAGE_ERROR || code==EXCEPTION_ILLEGAL_INSTRUCTION || code==EXCEPTION_STACK_OVERFLOW || code==0xC0000374 || code==0xC0000409){
        Record(Fault,code,reinterpret_cast<DWORD>(p->ExceptionRecord->ExceptionAddress));
        Critical(Fault,p);
        // Stack overflow/heap corruption: write the fixed breadcrumb only.
        if(code!=EXCEPTION_STACK_OVERFLOW && code!=0xC0000374)RequestDump();
    }
    return EXCEPTION_CONTINUE_SEARCH; // Never suppress or "fix" the exception.
}
int WSAAPI ReceiveHook(SOCKET s,char* buffer,int length,int flags){
    int result=realRecv(s,buffer,length,flags);int error=WSAGetLastError();
    InterlockedIncrement(&rxCalls);if(result>0)InterlockedExchangeAdd(&rxBytes,result);
    Record(Receive,static_cast<DWORD>(s),static_cast<DWORD>(result),result==SOCKET_ERROR?error:0);
    WSASetLastError(error);return result;
}
int WSAAPI SendHook(SOCKET s,const char* buffer,int length,int flags){
    int result=realSend(s,buffer,length,flags);int error=WSAGetLastError();
    InterlockedIncrement(&txCalls);if(result>0)InterlockedExchangeAdd(&txBytes,result);
    Record(Send,static_cast<DWORD>(s),static_cast<DWORD>(result),result==SOCKET_ERROR?error:0);
    WSASetLastError(error);return result;
}
int WSAAPI ConnectHook(SOCKET s,const sockaddr* address,int length){
    int result=realConnect(s,address,length);int error=WSAGetLastError();
    Record(Connect,static_cast<DWORD>(s),result,result==SOCKET_ERROR?error:0);WSASetLastError(error);return result;
}
int WSAAPI CloseHook(SOCKET s){
    int result=realClose(s);int error=WSAGetLastError();Record(Close,static_cast<DWORD>(s),result,result==SOCKET_ERROR?error:0);
    if(wake)SetEvent(wake);WSASetLastError(error);return result;
}
int WSAAPI ShutdownHook(SOCKET s,int how){
    int result=realShutdown(s,how);int error=WSAGetLastError();Record(Shutdown,static_cast<DWORD>(s),how,result==SOCKET_ERROR?error:0);WSASetLastError(error);return result;
}
int WSAAPI WSAReceiveHook(SOCKET s,LPWSABUF buffers,DWORD count,LPDWORD bytes,LPDWORD flags,LPWSAOVERLAPPED overlapped,LPWSAOVERLAPPED_COMPLETION_ROUTINE completion){
    int result=realWSARecv(s,buffers,count,bytes,flags,overlapped,completion);int error=WSAGetLastError();
    // Overlapped submissions are NOT counted as completed transfers.
    DWORD done=(result==0 && !overlapped && bytes)?*bytes:0;
    InterlockedIncrement(&rxCalls);if(done)InterlockedExchangeAdd(&rxBytes,done);
    Record(AsyncReceive,static_cast<DWORD>(s),done,result==SOCKET_ERROR?error:0);WSASetLastError(error);return result;
}
int WSAAPI WSASendHook(SOCKET s,LPWSABUF buffers,DWORD count,LPDWORD bytes,DWORD flags,LPWSAOVERLAPPED overlapped,LPWSAOVERLAPPED_COMPLETION_ROUTINE completion){
    int result=realWSASend(s,buffers,count,bytes,flags,overlapped,completion);int error=WSAGetLastError();
    DWORD done=(result==0 && !overlapped && bytes)?*bytes:0;
    InterlockedIncrement(&txCalls);if(done)InterlockedExchangeAdd(&txBytes,done);
    Record(AsyncSend,static_cast<DWORD>(s),done,result==SOCKET_ERROR?error:0);WSASetLastError(error);return result;
}
void WINAPI ExitHook(UINT code){
    InterlockedExchange(&stopping,1);Record(ProcessExit,code);Critical(ProcessExit,nullptr,code);RequestDump();realExit(code);
}
BOOL WINAPI TerminateHook(HANDLE process,UINT code){
    DWORD saved=GetLastError();
    if(GetProcessId(process)==GetCurrentProcessId()){Record(ProcessTerminate,code);Critical(ProcessTerminate,nullptr,code);RequestDump();}
    SetLastError(saved);return realTerminate(process,code);
}
bool InstallHooks(){
    LONG error=DetourTransactionBegin();if(error!=NO_ERROR){HookError("os_begin",error);return false;}
    std::vector<HANDLE> threads;
    bool okay=UpdateThreads(threads);
#define ATTACH(original,hook) if(okay){error=DetourAttach(reinterpret_cast<PVOID*>(&original),hook);if(error!=NO_ERROR){HookError(#hook,error);okay=false;}}
    ATTACH(realRecv,ReceiveHook);ATTACH(realSend,SendHook);ATTACH(realConnect,ConnectHook);
    ATTACH(realClose,CloseHook);ATTACH(realShutdown,ShutdownHook);
    ATTACH(realWSARecv,WSAReceiveHook);ATTACH(realWSASend,WSASendHook);
    ATTACH(realExit,ExitHook);ATTACH(realTerminate,TerminateHook);
#undef ATTACH
    if(okay){error=DetourTransactionCommit();okay=error==NO_ERROR;if(!okay)HookError("os_commit",error);}else DetourTransactionAbort();
    for(HANDLE thread:threads)CloseHandle(thread);
    return okay;
}
HANDLE logFile=INVALID_HANDLE_VALUE;DWORD logSize=0;unsigned logPart=0;
void Line(const char* message){
    if(logFile==INVALID_HANDLE_VALUE)return;
    SYSTEMTIME now{};GetSystemTime(&now);char line[1800];
    int size=std::snprintf(line,sizeof(line),"%04u-%02u-%02uT%02u:%02u:%02u.%03uZ pid=%lu %s\r\n",now.wYear,now.wMonth,now.wDay,now.wHour,now.wMinute,now.wSecond,now.wMilliseconds,GetCurrentProcessId(),message);
    if(size<=0)return;DWORD length=static_cast<DWORD>(std::min(size,static_cast<int>(sizeof(line)-1))),written=0;
    if(logSize+length>4*1024*1024){
        FlushFileBuffers(logFile);CloseHandle(logFile);
        wchar_t rotated[MAX_PATH];swprintf_s(rotated,L"%s.previous",logPath);MoveFileExW(logPath,rotated,MOVEFILE_REPLACE_EXISTING);
        logFile=CreateFileW(logPath,GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);logSize=0;++logPart;
    }
    if(logFile!=INVALID_HANDLE_VALUE){WriteFile(logFile,line,length,&written,nullptr);logSize+=written;}
}
void HashLine(const wchar_t* path,const char* label){
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    BCRYPT_ALG_HANDLE algorithm=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;unsigned char digest[32],buffer[16384];DWORD read=0;
    bool okay=file!=INVALID_HANDLE_VALUE && BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0 && BCryptCreateHash(algorithm,&hash,nullptr,0,nullptr,0,0)>=0;
    if(okay){for(;;){if(!ReadFile(file,buffer,sizeof(buffer),&read,nullptr)){okay=false;break;}if(!read)break;if(BCryptHashData(hash,buffer,read,0)<0){okay=false;break;}}if(okay)okay=BCryptFinishHash(hash,digest,sizeof(digest),0)>=0;}
    char line[180]{};std::snprintf(line,sizeof(line),"identity %s sha256=",label);
    if(okay){size_t offset=strlen(line);for(unsigned i=0;i<32;++i)std::snprintf(line+offset+2*i,3,"%02X",digest[i]);if(!strcmp(label,"trainer_proxy") && !strcmp(line+offset,"A4DF286AF3058C2AFBAF2B421F2C8982E392E19F474697086D2E1BB278D30348")){proxyIsV5=true;verifiedProxy=GetModuleHandleW(path);}}else strcat_s(line,"unavailable");
    Line(line);if(hash)BCryptDestroyHash(hash);if(algorithm)BCryptCloseAlgorithmProvider(algorithm,0);if(file!=INVALID_HANDLE_VALUE)CloseHandle(file);
}
void Modules(){
    HMODULE modules[256];DWORD needed=0;
    if(!EnumProcessModules(GetCurrentProcess(),modules,sizeof(modules),&needed)){Line("modules unavailable");return;}
    for(DWORD i=0;i<std::min<DWORD>(needed/sizeof(HMODULE),256);++i){
        char path[MAX_PATH]{};MODULEINFO info{};GetModuleBaseNameA(GetCurrentProcess(),modules[i],path,MAX_PATH);GetModuleInformation(GetCurrentProcess(),modules[i],&info,sizeof(info));
        char line[420];std::snprintf(line,sizeof(line),"module name=%s base=%p size=%lu",path,info.lpBaseOfDll,info.SizeOfImage);Line(line);
    }
}
BOOL CALLBACK FindWindow(HWND window,LPARAM value){DWORD pid=0;GetWindowThreadProcessId(window,&pid);if(pid==GetCurrentProcessId() && IsWindowVisible(window)){*reinterpret_cast<HWND*>(value)=window;return FALSE;}return TRUE;}
void CaptureMini(){
    LONG index=InterlockedIncrement(&dumps);if(index>2){SetEvent(captured);return;}
    std::string dumpSession(session);auto pidToken=dumpSession.find("pid");if(pidToken!=std::string::npos)dumpSession.replace(pidToken,3,"p");
    wchar_t output[MAX_PATH];swprintf_s(output,L"%s\\capture-%S-%ld.dmp",root,dumpSession.c_str(),index);
    wchar_t command[3*MAX_PATH];swprintf_s(command,L"\"%s\" -accepteula -mm %lu \"%s\"",dumpTool,GetCurrentProcessId(),output);
    STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION child{};
    BOOL okay=CreateProcessW(dumpTool,command,nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,root,&startup,&child);
    char line[200];std::snprintf(line,sizeof(line),"minidump attempt=%ld launched=%d error=%lu (fault context is in .faults)",index,okay,okay?0:GetLastError());Line(line);FlushFileBuffers(logFile);
    if(okay){CloseHandle(child.hThread);DWORD wait=WaitForSingleObject(child.hProcess,5000),code=STILL_ACTIVE;GetExitCodeProcess(child.hProcess,&code);CloseHandle(child.hProcess);std::snprintf(line,sizeof(line),"minidump wait=%lu exit=%lu",wait,code);Line(line);}
    SetEvent(captured);
}
void Prune(const wchar_t* pattern,size_t retain){
    struct File {std::wstring path;ULONGLONG time;};std::vector<File> files;
    wchar_t query[MAX_PATH];swprintf_s(query,L"%s\\%s",root,pattern);WIN32_FIND_DATAW entry{};HANDLE find=FindFirstFileW(query,&entry);
    if(find==INVALID_HANDLE_VALUE)return;
    do{if(!(entry.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY)){files.push_back({std::wstring(root)+L"\\"+entry.cFileName,(static_cast<ULONGLONG>(entry.ftLastWriteTime.dwHighDateTime)<<32)|entry.ftLastWriteTime.dwLowDateTime});}}while(FindNextFileW(find,&entry));FindClose(find);
    std::sort(files.begin(),files.end(),[](const File& a,const File& b){return a.time>b.time;});
    for(size_t i=retain;i<files.size();++i)DeleteFileW(files[i].path.c_str()); // Only our nonrecursive filename patterns.
}
DWORD WINAPI Worker(void*){
    wchar_t exe[MAX_PATH],dll[MAX_PATH];GetModuleFileNameW(nullptr,exe,MAX_PATH);GetModuleFileNameW(selfModule,dll,MAX_PATH);
    const wchar_t* base=wcsrchr(exe,L'\\');base=base?base+1:exe;
    if(_wcsicmp(base,L"MapleStory.exe") && _wcsicmp(base,L"NativeDiagnosticsHarness.exe"))return 0;
    HMODULE pinned=nullptr;GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_PIN,reinterpret_cast<LPCWSTR>(&Worker),&pinned);
    wcscpy_s(root,dll);wchar_t* slash=wcsrchr(root,L'\\');if(!slash)return 0;*slash=0;
    swprintf_s(dumpTool,L"%s\\diagnostics-tools\\procdump.exe",root);
    wcscat_s(root,L"\\diagnostics");CreateDirectoryW(root,nullptr);
    Prune(L"session-*.log",7);Prune(L"session-*.log.previous",7);Prune(L"session-*.faults",7);Prune(L"session-*.lifecycle",7);Prune(L"capture-*.dmp",2);
    SYSTEMTIME now{};GetSystemTime(&now);std::snprintf(session,sizeof(session),"%04u%02u%02uT%02u%02u%02uZ-pid%lu",now.wYear,now.wMonth,now.wDay,now.wHour,now.wMinute,now.wSecond,GetCurrentProcessId());
    swprintf_s(logPath,L"%s\\session-%S.log",root,session);swprintf_s(faultPath,L"%s\\session-%S.faults",root,session);
    logFile=CreateFileW(logPath,GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);
    critical=CreateFileW(faultPath,GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_WRITE_THROUGH,nullptr);
    wchar_t codePath[MAX_PATH];swprintf_s(codePath,L"%s\\session-%S.code",root,session);
    faultCodeFile=CreateFileW(codePath,GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_WRITE_THROUGH,nullptr);
    wchar_t lifecyclePath[MAX_PATH];swprintf_s(lifecyclePath,L"%s\\session-%S.lifecycle",root,session);
    lifecycleFile=CreateFileW(lifecyclePath,GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_WRITE_THROUGH,nullptr);
    if(lifecycleFile!=INVALID_HANDLE_VALUE)firstNullFile=CreateFileW(lifecyclePath,GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_WRITE_THROUGH,nullptr);
    wake=CreateEventW(nullptr,FALSE,FALSE,nullptr);captured=CreateEventW(nullptr,TRUE,FALSE,nullptr);
    char line[512];std::snprintf(line,sizeof(line),"startup build=%s compiled=%s %s tid=%lu fault_record_size=%u",Build,__DATE__,__TIME__,GetCurrentThreadId(),static_cast<unsigned>(sizeof(FaultRecord)));Line(line);
    HashLine(exe,"exe");HashLine(dll,"diagnostics");wchar_t proxy[MAX_PATH];wcscpy_s(proxy,exe);slash=wcsrchr(proxy,L'\\');if(slash){wcscpy_s(slash+1,MAX_PATH-(slash+1-proxy),L"dinput8.dll");HashLine(proxy,"trainer_proxy");}
    Modules();Line("privacy: socket metadata only; no buffers/credentials/chat; opcode=unavailable encrypted transport; verified player-pool hook separately records decoded opcode/length; async completion counts unavailable; mini dumps may contain process memory and remain local");
    bool hooks=InstallHooks();PVOID veh=AddVectoredExceptionHandler(1,OnException);
    std::snprintf(line,sizeof(line),"ready os_hooks=%d veh=%d critical_file=%d; player-pool guard pending exact bytes; direct syscalls/external kills may bypass exit hooks",hooks,veh!=nullptr,critical!=INVALID_HANDLE_VALUE);Line(line);FlushFileBuffers(logFile);
    InterlockedExchange(&ready,hooks && veh && critical!=INVALID_HANDLE_VALUE?1:-1);
    gameHost=_wcsicmp(base,L"MapleStory.exe")==0;
    unsigned guardAttempts=0,lifecycleAttempts=0,errorFixAttempts=0;
    if(gameHost){++guardAttempts;InstallPoolGuard();InstallLifecycle();InstallNativeErrorFix();}
    LONG last=0;ULONGLONG nextModules=GetTickCount64()+30000;unsigned missedPings=0;bool stallRecorded=false;
    for(;;){
        WaitForSingleObject(wake,2000);
        if(gameHost && !InterlockedCompareExchange(&nativeErrorFixReady,0,0) && errorFixAttempts++<60){if(!InstallNativeErrorFix() && errorFixAttempts==60)Line("native_error_fix REJECTED: pinned v5 identity, bytes or suspended-thread update unavailable");}
        if(gameHost && !InterlockedCompareExchange(&lifecycleReady,0,0) && lifecycleAttempts++<60){if(!InstallLifecycle() && lifecycleAttempts==60)Line("lifecycle_trace REJECTED: exact bytes or hook transaction unavailable");}
        if(gameHost && !InterlockedCompareExchange(&poolGuardReady,0,0) && guardAttempts<60){++guardAttempts;if(!InstallPoolGuard() && guardAttempts==60)Line("player_pool_guard REJECTED after120s: exact bytes or hook transaction unavailable; fix is NOT active");}
        if(InterlockedExchange(&dumpRequested,0))CaptureMini();
        Event events[Capacity];DWORD count=Snapshot(events);std::sort(events,events+count,[](const Event& a,const Event& b){return a.sequence<b.sequence;});
        if(count && events[0].sequence>last+1){std::snprintf(line,sizeof(line),"ring_overwritten=%ld",events[0].sequence-last-1);Line(line);}
        for(DWORD i=0;i<count;++i)if(events[i].sequence>last){const auto& e=events[i];std::snprintf(line,sizeof(line),"event seq=%ld tick=%lu tid=%lu kind=%lu a=%lu b=%lu c=%lu",e.sequence,e.tick,e.thread,e.kind,e.a,e.b,e.c);Line(line);last=e.sequence;}
        PROCESS_MEMORY_COUNTERS_EX memory{};memory.cb=sizeof(memory);GetProcessMemoryInfo(GetCurrentProcess(),reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&memory),sizeof(memory));
        FILETIME creation{},exit{},kernel{},user{};GetProcessTimes(GetCurrentProcess(),&creation,&exit,&kernel,&user);ULARGE_INTEGER cpuK{},cpuU{};cpuK.LowPart=kernel.dwLowDateTime;cpuK.HighPart=kernel.dwHighDateTime;cpuU.LowPart=user.dwLowDateTime;cpuU.HighPart=user.dwHighDateTime;
        HWND window=nullptr;EnumWindows(FindWindow,reinterpret_cast<LPARAM>(&window));DWORD_PTR result=0;DWORD started=GetTickCount();BOOL responds=window?static_cast<BOOL>(SendMessageTimeoutW(window,WM_NULL,0,0,SMTO_ABORTIFHUNG|SMTO_BLOCK,100,&result)):FALSE;
        missedPings=(window && !responds)?missedPings+1:0;
        if(missedPings>=3 && !stallRecorded && !InterlockedCompareExchange(&stopping,0,0)){
            stallRecorded=true;Record(UiStall,missedPings);Critical(UiStall);
            Line("suspected_ui_stall: 3 consecutive heartbeat pings timed out; not proof of a crash");
            if(InterlockedCompareExchange(&dumps,0,0)<2)CaptureMini();
        }
        std::snprintf(line,sizeof(line),"heartbeat tick=%lu private=%zu working=%zu cpu100ns=%llu window=%p responsive=%d pingms=%lu rxCalls=%ld rxBytes=%lu txCalls=%ld txBytes=%lu ringLost=%ld criticalCount=%ld faultSlots=16 exitSlots=2 minidumps=%ld stopping=%ld",GetTickCount(),memory.PrivateUsage,memory.WorkingSetSize,cpuK.QuadPart+cpuU.QuadPart,window,responds,GetTickCount()-started,InterlockedCompareExchange(&rxCalls,0,0),static_cast<DWORD>(InterlockedCompareExchange(&rxBytes,0,0)),InterlockedCompareExchange(&txCalls,0,0),static_cast<DWORD>(InterlockedCompareExchange(&txBytes,0,0)),InterlockedCompareExchange(&lost,0,0),InterlockedCompareExchange(&faultCount,0,0),InterlockedCompareExchange(&dumps,0,0),InterlockedCompareExchange(&stopping,0,0));Line(line);
        if(GetTickCount64()>=nextModules){Modules();nextModules=GetTickCount64()+30000;}
        std::snprintf(line,sizeof(line),"player_pool_guard active=%ld null_packets_discarded=%ld",InterlockedCompareExchange(&poolGuardReady,0,0),InterlockedCompareExchange(&rejectedPoolPackets,0,0));Line(line);
        FlushFileBuffers(logFile);
    }
}
}
extern "C" __declspec(dllexport) LONG WINAPI DiagnosticsReady(){return InterlockedCompareExchange(&ready,0,0);}
extern "C" __declspec(dllexport) LONG WINAPI DiagnosticsErrorBranchTest(){
    if(gameHost)return 40;
    // Same TEST ESI/near conditional jump as pinned v5, wrapped in a cdecl
    // function returning whether a pending error reaches the handler.
    BYTE code[]={0x56,0x8b,0x74,0x24,0x08,0x85,0xf6,0x0f,0x89,0x07,0,0,0,0xb8,1,0,0,0,0xeb,0x02,0x33,0xc0,0x5e,0xc3};
    BYTE* memory=static_cast<BYTE*>(VirtualAlloc(nullptr,4096,MEM_RESERVE|MEM_COMMIT,PAGE_EXECUTE_READWRITE));if(!memory)return 41;
    memcpy(memory,code,sizeof(code));FlushInstructionCache(GetCurrentProcess(),memory,sizeof(code));
    auto call=reinterpret_cast<int(__cdecl*)(DWORD)>(memory);
    bool okay=call(0)==0 && call(5)==0 && call(0x21000000)==0 && call(0x80004005)==1;
    memory[8]=0x84;FlushInstructionCache(GetCurrentProcess(),memory,sizeof(code));
    const DWORD values[]={0,5,0x26,0x20000000,0x21000000,0x21000006,0x22000000,0x2200000d,0x80004005,0xffffffff};
    for(DWORD value:values)okay=okay && call(value)==(value!=0);
    VirtualFree(memory,0,MEM_RELEASE);return okay?0:42;
}
extern "C" __declspec(dllexport) LONG WINAPI DiagnosticsLifecycleTest(){
    if(gameHost)return 30;
    BYTE* code=static_cast<BYTE*>(VirtualAlloc(nullptr,4096,MEM_RESERVE|MEM_COMMIT,PAGE_EXECUTE_READWRITE));if(!code)return 31;
    // Two thiscall destructors, two cdecl clears, and a two-argument cdecl setter.
    BYTE dispose[]={0x55,0x8b,0xec,0xff,0x01,0x5d,0xc3};
    DWORD count1=0,count2=0,receiver1=0,receiver2=0,target=0;
    BYTE clear[]={0x55,0x8b,0xec,0xb8,0,0,0,0,0xff,0x00,0x5d,0xc3};
    BYTE setter[]={0x55,0x8b,0xec,0x8b,0x45,0x08,0x8b,0x55,0x0c,0x89,0x10,0x5d,0xc3};
    memcpy(code,dispose,sizeof(dispose));memcpy(code+32,dispose,sizeof(dispose));
    DWORD address=reinterpret_cast<DWORD>(&count1);memcpy(clear+4,&address,4);memcpy(code+64,clear,sizeof(clear));
    address=reinterpret_cast<DWORD>(&count2);memcpy(clear+4,&address,4);memcpy(code+96,clear,sizeof(clear));memcpy(code+128,setter,sizeof(setter));
    FlushInstructionCache(GetCurrentProcess(),code,160);
    auto d1=reinterpret_cast<Dispose>(code),d2=reinterpret_cast<Dispose>(code+32);auto c1=reinterpret_cast<Clear>(code+64),c2=reinterpret_cast<Clear>(code+96);auto setterCall=reinterpret_cast<SetStage>(code+128);
    realPlayerDispose=d1;realNpcDispose=d2;realPlayerClear=c1;realNpcClear=c2;realSetStage=setterCall;
    if(!SetLifecycleHooks(true)){VirtualFree(code,0,MEM_RELEASE);return 32;}
    DWORD before=0,after=0;
    __asm mov before,esp
    for(int i=0;i<100;++i){d1(&receiver1);d2(&receiver2);c1();c2();setterCall(&target,reinterpret_cast<void*>(0x12345678));}
    __asm mov after,esp
    bool okay=before==after && receiver1==100 && receiver2==100 && count1==100 && count2==100 && target==0x12345678;
    if(!SetLifecycleHooks(false))return 33;
    VirtualFree(code,0,MEM_RELEASE);return okay?0:34;
}
extern "C" __declspec(dllexport) LONG WINAPI DiagnosticsGuardTest(){
    wchar_t path[MAX_PATH];GetModuleFileNameW(nullptr,path,MAX_PATH);const wchar_t* base=wcsrchr(path,L'\\');
    if(!base || _wcsicmp(base+1,L"NativeDiagnosticsHarness.exe"))return 20;
    // A real x86 thiscall entry: prologue, store opcode/packet on the receiver,
    // and ret8. A null receiver would fault without the installed wrapper.
    const BYTE code[]={0x55,0x8b,0xec,0x8b,0x45,0x08,0x89,0x01,0x8b,0x45,0x0c,0x89,0x41,0x04,0x5d,0xc2,0x08,0x00};
    void* memory=VirtualAlloc(nullptr,4096,MEM_RESERVE|MEM_COMMIT,PAGE_EXECUTE_READWRITE);if(!memory)return 21;
    std::memcpy(memory,code,sizeof(code));FlushInstructionCache(GetCurrentProcess(),memory,sizeof(code));
    auto call=reinterpret_cast<PlayerDispatch>(memory);if(!SetPoolHook(call,true)){VirtualFree(memory,0,MEM_RELEASE);return 22;}
    DWORD receiver[2]{},packet[6]{};DWORD before=0,after=0;LONG rejectedBefore=InterlockedCompareExchange(&rejectedPoolPackets,0,0);
    __asm mov before,esp
    for(unsigned i=0;i<10000;++i){call(nullptr,0xb9,packet);call(receiver,0xb9,packet);}
    __asm mov after,esp
    bool okay=before==after && receiver[0]==0xb9 && receiver[1]==reinterpret_cast<DWORD>(packet) && InterlockedCompareExchange(&rejectedPoolPackets,0,0)-rejectedBefore==10000;
    if(!SetPoolHook(call,false))return 23;
    VirtualFree(memory,0,MEM_RELEASE);return okay?0:24;
}
BOOL WINAPI DllMain(HMODULE module,DWORD reason,LPVOID reserved){
    if(reason==DLL_PROCESS_ATTACH){selfModule=module;DisableThreadLibraryCalls(module);HANDLE thread=CreateThread(nullptr,0,Worker,nullptr,0,nullptr);if(thread)CloseHandle(thread);}
    if(reason==DLL_PROCESS_DETACH)Critical(100,nullptr,reserved?1:0);
    return TRUE;
}
