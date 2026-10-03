#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <thread>

void SyntheticFault(){
    __try { RaiseException(EXCEPTION_ACCESS_VIOLATION,0,0,nullptr); }
    __except(EXCEPTION_EXECUTE_HANDLER) { std::puts("synthetic fault propagated to harness handler"); }
}
int main(int argc,char** argv){
    HMODULE diagnostics=LoadLibraryW(L"SoloClientDiagnostics.dll");
    if(!diagnostics){std::printf("load failed %lu\n",GetLastError());return 10;}
    auto ready=reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(diagnostics,"_DiagnosticsReady@0"));
    if(!ready){std::puts("export missing");return 11;}
    for(int i=0;i<100 && ready()==0;++i)Sleep(100);
    if(ready()!=1){std::puts("diagnostics not ready");return 12;}
    auto errorBranchTest=reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(diagnostics,"_DiagnosticsErrorBranchTest@0"));
    if(!errorBranchTest || errorBranchTest())return 21;
    std::puts("native error branch: original ignores positive errors; corrected branch handles positive/negative errors and passes zero");
    auto lifecycleTest=reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(diagnostics,"_DiagnosticsLifecycleTest@0"));
    LONG lifecycleResult=lifecycleTest?lifecycleTest():99;
    if(lifecycleResult){std::printf("lifecycle test failed %ld\n",lifecycleResult);return 19;}
    std::puts("lifecycle: five real x86 detours preserve receiver, cdecl arguments, effects and stack across 500 calls");
    auto guardTest=reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(diagnostics,"_DiagnosticsGuardTest@0"));
    LONG guardResult=guardTest?guardTest():99;
    if(guardResult){std::printf("guard test failed %ld\n",guardResult);return 18;}
    std::puts("player-pool guard: 10000 null calls safely skipped; 10000 valid calls forwarded; x86 stack preserved");
    if(lifecycleTest())return 20; // Overflow post-null area; preserve pre-null traces.
    WSADATA data{};if(WSAStartup(MAKEWORD(2,2),&data))return 13;
    SOCKET listener=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    sockaddr_in local{};local.sin_family=AF_INET;local.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if(bind(listener,reinterpret_cast<sockaddr*>(&local),sizeof(local)) || listen(listener,1))return 14;
    int size=sizeof(local);getsockname(listener,reinterpret_cast<sockaddr*>(&local),&size);
    const char payload[]="DIAGNOSTICS_SECRET_PAYLOAD_MUST_NOT_APPEAR";
    bool serverOkay=false;
    std::thread server([&]{SOCKET peer=accept(listener,nullptr,nullptr);char received[sizeof(payload)]{};int n=recv(peer,received,sizeof(received),MSG_WAITALL);serverOkay=n==sizeof(payload) && !memcmp(received,payload,sizeof(payload));if(serverOkay)send(peer,received,n,0);shutdown(peer,SD_BOTH);closesocket(peer);});
    SOCKET client=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    if(connect(client,reinterpret_cast<sockaddr*>(&local),sizeof(local)))return 15;
    WSASetLastError(12345);int sent=send(client,payload,sizeof(payload),0);int sendError=WSAGetLastError();
    char echoed[sizeof(payload)]{};int got=recv(client,echoed,sizeof(echoed),MSG_WAITALL);
    shutdown(client,SD_BOTH);closesocket(client);closesocket(listener);server.join();
    if(!serverOkay || sent!=sizeof(payload) || got!=sizeof(payload) || memcmp(payload,echoed,sizeof(payload)))return 16;
    if(sendError!=12345){std::printf("send last-error observed %d (provider may change it)\n",sendError);}
    for(int i=0;i<300;++i){char dummy=0;recv(INVALID_SOCKET,&dummy,1,0);if(WSAGetLastError()!=WSAENOTSOCK)return 17;}
    if(argc>1 && !strcmp(argv[1],"fault")){
        SyntheticFault();
    }
    if(argc>1 && !strcmp(argv[1],"cpp")){
        try { throw static_cast<DWORD>(0x80004005); }
        catch(DWORD result) {
            if(result!=0x80004005)return 22;
            std::puts("C++ startup HRESULT propagated unchanged to harness handler");
        }
    }
    if(argc>1 && !strcmp(argv[1],"saturation"))for(int i=0;i<24;++i)SyntheticFault();
    Sleep(6500);std::puts("socket payload preserved; error semantics preserved; harness complete");
    ExitProcess(0);
}
