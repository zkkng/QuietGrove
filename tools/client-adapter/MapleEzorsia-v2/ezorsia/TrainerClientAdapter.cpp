#include "stdafx.h"
#include "TrainerClientAdapter.h"
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <mutex>
#include <memory>
#include <vector>
#include <sddl.h>
#include <sstream>
#include <cmath>

// v83 snapshot sites and local/input/map offsets were identified using
// jnpl95/Timelapse (GPL-3.0): https://github.com/jnpl95/Timelapse
// The hooks below are new implementation code for the existing Ezorsia plugin.

namespace {
constexpr DWORD kFlyX = 0x009B62ED;
constexpr DWORD kFlyY = 0x009B6352;
constexpr DWORD kUnlimitedAttack = 0x009536E0;
// Exact v83 CUser::SetAttackAction prologue. This is independent of the
// stationary-attack counter above: it removes the local recovery gate only.
constexpr DWORD kNoAttackDelay = 0x0092EDB2;
constexpr DWORD kSkillEffect = 0x00933990;
// CUserLocal::FallDown: permits the normal Down+Jump input on ordinary tiles.
// Local actor only; the original fall/foothold/destination code still runs.
constexpr DWORD kFallThrough = 0x0094C6EE;
// Exact runtime prologue/empty-return bytes captured from owned v83 after the
// 2026-09-30 hang. Its AV at 0x973744 read 0x10 (low/null lookup receiver).
constexpr DWORD kLookupEntry = 0x00973741;
DWORD kLookupReturn = kLookupEntry + 7;
constexpr unsigned char kLookupOriginal[7] = { 0x56, 0x8B, 0xF1, 0x83, 0x7E, 0x04, 0x00 };
volatile LONG rejectedLookupReceivers = 0;
LONG reportedLookupReceivers = 0;
constexpr unsigned char kFallThroughOriginal[2] = { 0x74, 0x1E };
constexpr unsigned char kFallThroughEnabled[2] = { 0x90, 0x90 };
constexpr unsigned char kSkillEffectOriginal[5] = { 0xB8, 0x34, 0xC3, 0xAD, 0x00 };
constexpr unsigned char kSkillEffectHidden[5] = { 0xC2, 0x14, 0x00, 0x90, 0x90 };
constexpr unsigned char kNoAttackDelayOriginal[10] =
    { 0xB8, 0x88, 0xB7, 0xAD, 0x00, 0xE8, 0xDC, 0x1D, 0x13, 0x00 };
constexpr unsigned char kNoAttackDelayEnabled[10] =
    { 0x6A, 0x01, 0x58, 0xC2, 0x10, 0x00, 0x90, 0x90, 0x90, 0x90 };
DWORD kFlyXReturn = kFlyX + 5;
DWORD kFlyYReturn = kFlyY + 5;
constexpr unsigned char kFlyXOriginal[5] = { 0x89, 0x03, 0x8B, 0x7D, 0x10 };
constexpr unsigned char kFlyYOriginal[5] = { 0x89, 0x07, 0x8B, 0x5D, 0x14 };
volatile LONG flyEnabled = 0, flySteering = 0, flyTargetX = 0, flyTargetY = 0;
int flySpeed = 600, flyDeadZone = 8, flyInertia = 40;
bool flyVertical = true;
double flyX = 0, flyY = 0, flyVelocityX = 0, flyVelocityY = 0;
ULONGLONG lastFlyTick = 0;
volatile LONG hoverEnabled = 0, hoverY = 0, snapshotX = 0, snapshotY = 0, snapshotSeen = 0;
DWORD physicalSpace = 0;
ULONGLONG lastSnapshotAt = 0, lastCpuFrame = 0;
unsigned long movementResets = 0;
bool cpuMode = false;
int backgroundFps = 15;
bool flySupported = false;
bool unlimitedSupported = false;
bool rapidSupported = false;
bool skillEffectsSupported = false;
bool fallThroughSupported = false, fallThroughEnabled = false;
std::mutex commandMutex;
struct PendingCommand {
    std::string text, response;
    HANDLE done = CreateEventA(nullptr, TRUE, FALSE, nullptr);
    std::atomic<bool> cancelled{false};
    explicit PendingCommand(const char* value) : text(value) { }
    ~PendingCommand() { if (done) CloseHandle(done); }
};
std::shared_ptr<PendingCommand> pendingCommand;
std::atomic<bool> started{false}, initialized{false}, resetRequested{false};
std::atomic<ULONGLONG> lastContact{0};
std::atomic<unsigned long> leaseResets{0};

void logEvent(const char* message) {
    OutputDebugStringA(message);
    OutputDebugStringA("\n");
    HANDLE file = CreateFileA("SoloTrainerClient.log", FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return;
    SYSTEMTIME time{};
    GetLocalTime(&time);
    char line[256]{};
    int length = std::snprintf(line, sizeof(line),
        "[%04u-%02u-%02u %02u:%02u:%02u pid=%lu] %s\r\n", time.wYear, time.wMonth,
        time.wDay, time.wHour, time.wMinute, time.wSecond, GetCurrentProcessId(), message);
    if (length > 0) {
        DWORD written = 0;
        const DWORD boundedLength = static_cast<DWORD>(length < sizeof(line) ? length : sizeof(line) - 1);
        WriteFile(file, line, boundedLength, &written, nullptr);
    }
    CloseHandle(file);
}

bool readable(DWORD address, size_t length) {
    MEMORY_BASIC_INFORMATION info{};
    if (VirtualQuery(reinterpret_cast<void*>(address), &info, sizeof(info)) != sizeof(info)) return false;
    auto end = static_cast<unsigned long long>(address) + length;
    auto regionEnd = reinterpret_cast<unsigned long long>(info.BaseAddress) + info.RegionSize;
    return info.State == MEM_COMMIT && end <= regionEnd
        && !(info.Protect & (PAGE_NOACCESS | PAGE_GUARD));
}

template<size_t N>
bool patchJump(DWORD address, const void* target, const unsigned char (&expected)[N]) {
    static_assert(N >= 5, "A relative jump requires five bytes");
    if (!readable(address, N) || std::memcmp(reinterpret_cast<void*>(address), expected, N) != 0) return false;
    unsigned char jump[N]; std::memset(jump, 0x90, N); jump[0] = 0xE9;
    auto relative = static_cast<DWORD>(reinterpret_cast<DWORD>(target) - address - 5);
    std::memcpy(jump + 1, &relative, sizeof(relative));
    DWORD oldProtection = 0;
    if (!VirtualProtect(reinterpret_cast<void*>(address), N, PAGE_EXECUTE_READWRITE, &oldProtection)) return false;
    std::memcpy(reinterpret_cast<void*>(address), jump, N);
    FlushInstructionCache(GetCurrentProcess(), reinterpret_cast<void*>(address), N);
    DWORD ignored = 0;
    VirtualProtect(reinterpret_cast<void*>(address), N, oldProtection, &ignored);
    return true;
}

__declspec(naked) void lookupReceiverGuard() {
    __asm {
        cmp ecx, 10000h
        jb invalidReceiver
        push esi
        mov esi, ecx
        cmp dword ptr [esi + 4], 0
        jmp dword ptr [kLookupReturn]
    invalidReceiver:
        lock inc dword ptr [rejectedLookupReceivers]
        xor eax, eax
        ret 8
    }
}

bool installLookupGuard() {
    // Low receivers use exactly the normal empty-lookup nullptr/ret-8 contract.
    constexpr unsigned char emptyReturn[7] = { 0x33, 0xC0, 0x5F, 0x5E, 0xC2, 0x08, 0x00 };
    return readable(0x00973778, sizeof(emptyReturn))
        && std::memcmp(reinterpret_cast<void*>(0x00973778), emptyReturn, sizeof(emptyReturn)) == 0
        && patchJump(kLookupEntry, lookupReceiverGuard, kLookupOriginal);
}

bool setUnlimitedAttack(bool enabled) {
    if (!unlimitedSupported || !readable(kUnlimitedAttack, 1)) return false;
    auto address = reinterpret_cast<unsigned char*>(kUnlimitedAttack);
    unsigned char expected = enabled ? 0x7E : 0xEB;
    unsigned char desired = enabled ? 0xEB : 0x7E;
    if (*address == desired) return true;
    if (*address != expected) return false;
    DWORD oldProtection = 0;
    if (!VirtualProtect(address, 1, PAGE_EXECUTE_READWRITE, &oldProtection)) return false;
    *address = desired;
    FlushInstructionCache(GetCurrentProcess(), address, 1);
    DWORD ignored = 0;
    VirtualProtect(address, 1, oldProtection, &ignored);
    return true;
}

bool setRapidAttack(bool enabled) {
    if (!rapidSupported || !readable(kNoAttackDelay, sizeof(kNoAttackDelayOriginal))) return false;
    auto address = reinterpret_cast<unsigned char*>(kNoAttackDelay);
    const auto* expected = enabled ? kNoAttackDelayOriginal : kNoAttackDelayEnabled;
    const auto* desired = enabled ? kNoAttackDelayEnabled : kNoAttackDelayOriginal;
    if (std::memcmp(address, desired, sizeof(kNoAttackDelayOriginal)) == 0) return true;
    if (std::memcmp(address, expected, sizeof(kNoAttackDelayOriginal)) != 0) return false;
    DWORD oldProtection = 0;
    if (!VirtualProtect(address, sizeof(kNoAttackDelayOriginal), PAGE_EXECUTE_READWRITE, &oldProtection)) return false;
    std::memcpy(address, desired, sizeof(kNoAttackDelayOriginal));
    FlushInstructionCache(GetCurrentProcess(), address, sizeof(kNoAttackDelayOriginal));
    DWORD ignored = 0;
    VirtualProtect(address, sizeof(kNoAttackDelayOriginal), oldProtection, &ignored);
    return true;
}

bool setSkillEffectsHidden(bool hidden) {
    if (!skillEffectsSupported || !readable(kSkillEffect, sizeof(kSkillEffectOriginal))) return false;
    auto address = reinterpret_cast<unsigned char*>(kSkillEffect);
    const auto* expected = hidden ? kSkillEffectOriginal : kSkillEffectHidden;
    const auto* desired = hidden ? kSkillEffectHidden : kSkillEffectOriginal;
    if (std::memcmp(address, desired, sizeof(kSkillEffectOriginal)) == 0) return true;
    if (std::memcmp(address, expected, sizeof(kSkillEffectOriginal)) != 0) return false;
    DWORD protection = 0;
    if (!VirtualProtect(address, sizeof(kSkillEffectOriginal), PAGE_EXECUTE_READWRITE, &protection)) return false;
    std::memcpy(address, desired, sizeof(kSkillEffectOriginal));
    FlushInstructionCache(GetCurrentProcess(), address, sizeof(kSkillEffectOriginal));
    DWORD ignored = 0;
    VirtualProtect(address, sizeof(kSkillEffectOriginal), protection, &ignored);
    return true;
}

bool setFallThrough(bool enabled) {
    if (!fallThroughSupported || !readable(kFallThrough, sizeof(kFallThroughOriginal))) return false;
    auto address = reinterpret_cast<unsigned char*>(kFallThrough);
    const auto* expected = enabled ? kFallThroughOriginal : kFallThroughEnabled;
    const auto* desired = enabled ? kFallThroughEnabled : kFallThroughOriginal;
    if (std::memcmp(address, desired, sizeof(kFallThroughOriginal)) != 0) {
        if (std::memcmp(address, expected, sizeof(kFallThroughOriginal)) != 0) return false;
        DWORD protection = 0;
        if (!VirtualProtect(address, sizeof(kFallThroughOriginal), PAGE_EXECUTE_READWRITE, &protection)) return false;
        std::memcpy(address, desired, sizeof(kFallThroughOriginal));
        FlushInstructionCache(GetCurrentProcess(), address, sizeof(kFallThroughOriginal));
        DWORD ignored = 0;
        VirtualProtect(address, sizeof(kFallThroughOriginal), protection, &ignored);
    }
    fallThroughEnabled = enabled;
    return true;
}

// Both hooks run only for the local player's vector controller. They preserve
// the original writes when fly is off, so OFF never needs a live code rewrite.
__declspec(naked) void flyXHook() {
    __asm {
        pushfd
        push ecx
        push edx
        test ebx, ebx
        je noXOutput
        mov ecx, dword ptr ds:[0x00BEBF98]
        test ecx, ecx
        je original
        mov ecx, [ecx + 0x11A4]
        cmp esi, ecx
        jne original
        cmp dword ptr [flySteering], 0
        je capture
        mov eax, dword ptr [flyTargetX]
    capture:
        mov dword ptr [snapshotX], eax
    original:
        pop edx
        pop ecx
        popfd
        mov [ebx], eax
        mov edi, [ebp + 0x10]
        jmp dword ptr [kFlyXReturn]
    noXOutput:
        pop edx
        pop ecx
        popfd
        mov edi, [ebp + 0x10]
        jmp dword ptr [kFlyXReturn]
    }
}

__declspec(naked) void flyYHook() {
    __asm {
        pushfd
        push ecx
        push edx
        test edi, edi
        je noYOutput
        mov ecx, dword ptr ds:[0x00BEBF98]
        test ecx, ecx
        je original
        mov ecx, [ecx + 0x11A4]
        cmp esi, ecx
        jne original
        cmp dword ptr [hoverEnabled], 0
        je mouseMode
        mov eax, dword ptr [hoverY]
        jmp bounds
    mouseMode:
        cmp dword ptr [flySteering], 0
        je capture
        mov eax, dword ptr [flyTargetY]
    bounds:
        mov edx, dword ptr ds:[0x00BEBFA0]
        test edx, edx
        je original
        mov ecx, [edx + 0x28]
        cmp eax, ecx
        jge checkBottom
        mov eax, ecx
    checkBottom:
        mov ecx, [edx + 0x30]
        cmp eax, ecx
        jle capture
        mov eax, ecx
    capture:
        mov dword ptr [snapshotY], eax
        mov dword ptr [snapshotSeen], 1
    original:
        pop edx
        pop ecx
        popfd
        mov [edi], eax
        mov ebx, [ebp + 0x14]
        jmp dword ptr [kFlyYReturn]
    noYOutput:
        pop edx
        pop ecx
        popfd
        mov ebx, [ebp + 0x14]
        jmp dword ptr [kFlyYReturn]
    }
}

bool installFly() {
    // These branches originally skip the optional output write and enter the
    // second MOV. A five-byte JMP overwrites that landing instruction. Redirect
    // them to the guarded hook entry, which preserves the no-output path.
    constexpr unsigned char xBranch[2] = { 0x74, 0x58 };
    constexpr unsigned char yBranch[2] = { 0x74, 0x5E };
    if (!readable(kFlyX, 5) || !readable(kFlyY, 5)
        || !readable(0x009B6295, 2) || !readable(0x009B62F4, 2)
        || std::memcmp(reinterpret_cast<void*>(0x009B6295), xBranch, 2) != 0
        || std::memcmp(reinterpret_cast<void*>(0x009B62F4), yBranch, 2) != 0
        || std::memcmp(reinterpret_cast<void*>(kFlyX), kFlyXOriginal, 5) != 0
        || std::memcmp(reinterpret_cast<void*>(kFlyY), kFlyYOriginal, 5) != 0) return false;
    // One startup-only protection window avoids a partially installed pair.
    // No flight state is enabled during installation.
    DWORD oldProtection = 0;
    constexpr DWORD start = 0x009B6295, length = 0x009B6357-start;
    if (!VirtualProtect(reinterpret_cast<void*>(start), length, PAGE_EXECUTE_READWRITE, &oldProtection)) return false;
    unsigned char xJump[5] = { 0xE9 }, yJump[5] = { 0xE9 };
    const DWORD xRelative = reinterpret_cast<DWORD>(flyXHook)-kFlyX-5;
    const DWORD yRelative = reinterpret_cast<DWORD>(flyYHook)-kFlyY-5;
    std::memcpy(xJump+1, &xRelative, 4); std::memcpy(yJump+1, &yRelative, 4);
    std::memcpy(reinterpret_cast<void*>(kFlyX), xJump, 5);
    std::memcpy(reinterpret_cast<void*>(kFlyY), yJump, 5);
    *reinterpret_cast<unsigned char*>(0x009B6296) = 0x56;
    *reinterpret_cast<unsigned char*>(0x009B62F5) = 0x5C;
    FlushInstructionCache(GetCurrentProcess(), reinterpret_cast<void*>(start), length);
    DWORD ignored = 0;
    if (!VirtualProtect(reinterpret_cast<void*>(start), length, oldProtection, &ignored)) {
        // Still writable: restore every original instruction before rejection.
        std::memcpy(reinterpret_cast<void*>(kFlyX), kFlyXOriginal, 5);
        std::memcpy(reinterpret_cast<void*>(kFlyY), kFlyYOriginal, 5);
        std::memcpy(reinterpret_cast<void*>(start), xBranch, 2);
        std::memcpy(reinterpret_cast<void*>(0x009B62F4), yBranch, 2);
        FlushInstructionCache(GetCurrentProcess(), reinterpret_cast<void*>(start), length);
        VirtualProtect(reinterpret_cast<void*>(start), length, oldProtection, &ignored);
        return false;
    }
    return true;
}

void reply(HANDLE pipe, const char* message) {
    DWORD written = 0;
    WriteFile(pipe, message, static_cast<DWORD>(std::strlen(message)), &written, nullptr);
}

bool resetControls() {
    InterlockedExchange(&flyEnabled, 0); InterlockedExchange(&flySteering, 0);
    InterlockedExchange(&hoverEnabled, 0);
    cpuMode = false;
    const bool unlimitedOff = !unlimitedSupported || setUnlimitedAttack(false);
    const bool rapidOff = !rapidSupported || setRapidAttack(false);
    const bool effectsOff = !skillEffectsSupported || setSkillEffectsHidden(false);
    const bool fallOff = !fallThroughSupported || setFallThrough(false);
    return unlimitedOff && rapidOff && effectsOff && fallOff;
}

// Executed exclusively at the top of the existing CWvsApp::Run loop.
void updateFlySteering() {
    const ULONGLONG now = GetTickCount64();
    HWND foreground = GetForegroundWindow(); DWORD process = 0;
    GetWindowThreadProcessId(foreground, &process);
    POINT cursor{}; RECT client{};
    bool allowed = flyEnabled && physicalSpace && lastSnapshotAt && now - lastSnapshotAt <= 1000
        && process == GetCurrentProcessId() && (GetAsyncKeyState(VK_MENU) & 0x8000)
        && GetCursorPos(&cursor) && WindowFromPoint(cursor) == foreground
        && ScreenToClient(foreground, &cursor) && GetClientRect(foreground, &client)
        && PtInRect(&client, cursor);
    // Fixed top HUD/minimap and bottom status/chat regions never steer. Custom movable
    // game panels require additional hit-testing before claiming full UI exclusion.
    allowed = allowed && cursor.y > 32 && cursor.y < client.bottom - 145
        && !(cursor.x < 225 && cursor.y < 200);
    DWORD input = readable(0x00BEC33C, sizeof(DWORD)) ? *reinterpret_cast<DWORD*>(0x00BEC33C) : 0;
    DWORD vector = input && readable(input + 0x978, sizeof(DWORD)) ? *reinterpret_cast<DWORD*>(input + 0x978) : 0;
    if (!allowed || !vector || !readable(vector + 0x8C, 8) || !readable(physicalSpace + 0x24, 16)) {
        InterlockedExchange(&flySteering, 0); lastFlyTick = 0; flyVelocityX = flyVelocityY = 0; return;
    }
    if (!flySteering || !lastFlyTick) {
        flyX = snapshotX; flyY = snapshotY; flyVelocityX = flyVelocityY = 0;
        lastFlyTick = now; flyTargetX = snapshotX; flyTargetY = snapshotY;
        InterlockedExchange(&flySteering, 1); return;
    }
    double dt = (now - lastFlyTick) / 1000.0; lastFlyTick = now;
    if (dt <= 0) return; if (dt > 0.05) dt = 0.05; // never catch up after a stall
    double dx = *reinterpret_cast<LONG*>(vector + 0x8C) - flyX;
    double dy = flyVertical ? *reinterpret_cast<LONG*>(vector + 0x90) - flyY : 0;
    double distance = std::sqrt(dx*dx + dy*dy);
    double vx = 0, vy = 0;
    if (distance > flyDeadZone) { vx = dx / distance * flySpeed; vy = dy / distance * flySpeed; }
    double blend = flyInertia == 0 ? 1.0 : 1.0 - std::pow(flyInertia / 100.0, dt * 60.0);
    flyVelocityX += (vx - flyVelocityX) * blend; flyVelocityY += (vy - flyVelocityY) * blend;
    double stepX = flyVelocityX * dt, stepY = flyVelocityY * dt;
    double step = std::sqrt(stepX*stepX + stepY*stepY);
    if (step > distance && step > 0) { stepX *= distance/step; stepY *= distance/step; }
    flyX += stepX; flyY += stepY;
    LONG left = *reinterpret_cast<LONG*>(physicalSpace + 0x24), top = *reinterpret_cast<LONG*>(physicalSpace + 0x28);
    LONG right = *reinterpret_cast<LONG*>(physicalSpace + 0x2C), bottom = *reinterpret_cast<LONG*>(physicalSpace + 0x30);
    if (left > right || top > bottom) { InterlockedExchange(&flySteering, 0); lastFlyTick = 0; return; }
    if (flyX < left) flyX = left; if (flyX > right) flyX = right;
    if (flyY < top) flyY = top; if (flyY > bottom) flyY = bottom;
    flyTargetX = static_cast<LONG>(std::lround(flyX)); flyTargetY = static_cast<LONG>(std::lround(flyY));
}

std::string executeCommand(const std::string& command) {
    if (command == "PING") {
        lastContact.store(GetTickCount64());
        char state[256]{};
        std::snprintf(state, sizeof(state), "OK FLY=%d UNLIMITED=%d RAPID=%d SKILLFX=%d FALLTHROUGH=%d RESET=%lu GAME_THREAD=1 HOVER=%d CPU=1 FLYCFG=1 MRESET=%lu",
            flySupported ? 1 : 0, unlimitedSupported ? 1 : 0, rapidSupported ? 1 : 0,
            skillEffectsSupported ? 1 : 0, fallThroughSupported ? 1 : 0, leaseResets.load(), flySupported ? 1 : 0, movementResets);
        return state;
    }
    if (command == "OFF") {
        const bool success = resetControls();
        logEvent(success ? "All client controls OFF" : "Client reset incomplete");
        return success ? "OK OFF" : "ERR RESET_INCOMPLETE";
    }
    if (!lastContact.load()) return "ERR LEASE_EXPIRED";
    lastContact.store(GetTickCount64());
    if (command == "FLY 1" || command == "FLY 0") {
        const bool enabled = command.back() == '1';
        if (enabled && !flySupported) return "ERR FLY_UNSUPPORTED";
        if (enabled && (fallThroughEnabled || hoverEnabled)) return "ERR MOVEMENT_CONFLICT";
        InterlockedExchange(&flyEnabled, enabled ? 1 : 0);
        InterlockedExchange(&flySteering, 0); lastFlyTick = 0;
        return enabled ? "OK FLY=1" : "OK FLY=0";
    }
    if (command.compare(0, 7, "FLYOPT ") == 0) {
        std::istringstream values(command.substr(7)); int speed, dead, inertia, vertical; char extra;
        if (!(values >> speed >> dead >> inertia >> vertical) || (values >> extra)
            || speed < 50 || speed > 2000 || dead < 0 || dead > 100 || inertia < 0 || inertia > 95
            || (vertical != 0 && vertical != 1)) return "ERR FLY_OPTIONS";
        flySpeed = speed; flyDeadZone = dead; flyInertia = inertia; flyVertical = vertical != 0;
        InterlockedExchange(&flySteering, 0); lastFlyTick = 0;
        return "OK FLYOPT";
    }
    if (command == "UNLIMITED 1" || command == "UNLIMITED 0") {
        const bool enabled = command.back() == '1';
        if (!setUnlimitedAttack(enabled)) return "ERR UNLIMITED_UNSUPPORTED";
        return enabled ? "OK UNLIMITED=1" : "OK UNLIMITED=0";
    }
    if (command == "RAPID 1" || command == "RAPID 0") {
        const bool enabled = command.back() == '1';
        if (!setRapidAttack(enabled)) return "ERR RAPID_UNSUPPORTED";
        return enabled ? "OK RAPID=1" : "OK RAPID=0";
    }
    if (command == "MOTIONOFF") {
        InterlockedExchange(&flyEnabled, 0); InterlockedExchange(&flySteering, 0); InterlockedExchange(&hoverEnabled, 0);
        bool restored = !fallThroughSupported || setFallThrough(false);
        ++movementResets;
        return restored ? "OK MOTIONOFF" : "ERR RESET_INCOMPLETE";
    }
    if (command == "SKILLFX 1" || command == "SKILLFX 0") {
        const bool hidden = command.back() == '1';
        if (!setSkillEffectsHidden(hidden)) return "ERR SKILLFX_UNSUPPORTED";
        return hidden ? "OK SKILLFX=1" : "OK SKILLFX=0";
    }
    if (command == "FALLTHROUGH 1" || command == "FALLTHROUGH 0") {
        const bool enabled = command.back() == '1';
        if (enabled && (InterlockedCompareExchange(&flyEnabled, 0, 0) || hoverEnabled)) return "ERR MOVEMENT_CONFLICT";
        if (!setFallThrough(enabled)) return "ERR FALLTHROUGH_UNSUPPORTED";
        return enabled ? "OK FALLTHROUGH=1" : "OK FALLTHROUGH=0";
    }
    if (command == "HOVER 1" || command == "HOVER 0") {
        bool enabled = command.back() == '1';
        if (enabled && (!flySupported || !physicalSpace || !lastSnapshotAt || GetTickCount64() - lastSnapshotAt > 1000))
            return "ERR POSITION_UNAVAILABLE";
        if (enabled && (flyEnabled || fallThroughEnabled)) return "ERR MOVEMENT_CONFLICT";
        if (enabled) hoverY = snapshotY;
        InterlockedExchange(&hoverEnabled, enabled ? 1 : 0);
        return enabled ? "OK HOVER=1" : "OK HOVER=0";
    }
    if (command.compare(0, 4, "CPU ") == 0) {
        std::istringstream fields(command.substr(4)); int enabled, fps; char extra;
        if (!(fields >> enabled >> fps) || (fields >> extra) || (enabled != 0 && enabled != 1) || fps < 10 || fps > 60)
            return "ERR CPU_OPTIONS";
        cpuMode = enabled != 0; backgroundFps = fps; lastCpuFrame = 0;
        return cpuMode ? "OK CPU=1" : "OK CPU=0";
    }
    return "ERR UNKNOWN_COMMAND";
}

std::string requestCommand(const char* command) {
    auto request = std::make_shared<PendingCommand>(command);
    if (!request->done) return "ERR CLIENT_QUEUE_UNAVAILABLE";
    {
        std::lock_guard<std::mutex> guard(commandMutex);
        if (pendingCommand) return "ERR CLIENT_QUEUE_BUSY";
        pendingCommand = request;
    }
    if (WaitForSingleObject(request->done, 750) == WAIT_OBJECT_0) return request->response;
    request->cancelled.store(true);
    resetRequested.store(true);
    return "ERR GAME_THREAD_TIMEOUT";
}

DWORD WINAPI leaseWatchdog(void*) {
    while (true) {
        Sleep(100);
        const auto last = lastContact.load();
        if (!last || GetTickCount64() - last <= 5000) continue;
        // Atomic physics flag is safe immediately. Code patches are restored
        // on the game thread before its next input/simulation iteration.
        InterlockedExchange(&flyEnabled, 0); InterlockedExchange(&flySteering, 0);
        InterlockedExchange(&hoverEnabled, 0);
        resetRequested.store(true);
    }
}

DWORD WINAPI pipeWorker(void*) {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return 1;
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &size);
    std::vector<unsigned char> tokenData(size);
    if (!size || !GetTokenInformation(token, TokenUser, tokenData.data(), size, &size)) {
        CloseHandle(token); return 1;
    }
    CloseHandle(token);
    LPSTR sid = nullptr;
    if (!ConvertSidToStringSidA(reinterpret_cast<TOKEN_USER*>(tokenData.data())->User.Sid, &sid)) return 1;
    std::string dacl = std::string("D:P(A;;GA;;;SY)(A;;GA;;;") + sid + ")";
    LocalFree(sid);
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorA(dacl.c_str(), SDDL_REVISION_1, &descriptor, nullptr)) return 1;
    SECURITY_ATTRIBUTES security{ sizeof(SECURITY_ATTRIBUTES), descriptor, FALSE };
    char path[96]{};
    std::snprintf(path, sizeof(path), "\\\\.\\pipe\\SoloTrainerClient-%lu", GetCurrentProcessId());
    while (true) {
        HANDLE pipe = CreateNamedPipeA(path, PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE,
            PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1, 256, 256, 0, &security);
        if (pipe == INVALID_HANDLE_VALUE) break;
        if (ConnectNamedPipe(pipe, nullptr) || GetLastError() == ERROR_PIPE_CONNECTED) {
            logEvent("Trainer client adapter connected");
            char command[64]{};
            DWORD read = 0;
            while (ReadFile(pipe, command, sizeof(command) - 1, &read, nullptr) && read > 0) {
                command[read] = '\0';
                const std::string response = requestCommand(command);
                reply(pipe, response.c_str());
                read = 0;
            }
        }
        // Close/disconnect requests are drained before the next game update.
        InterlockedExchange(&flyEnabled, 0); InterlockedExchange(&flySteering, 0);
        InterlockedExchange(&hoverEnabled, 0);
        resetRequested.store(true);
        logEvent("Trainer client adapter disconnected; mouse fly OFF");
        DisconnectNamedPipe(pipe);
        CloseHandle(pipe);
    }
    LocalFree(descriptor);
    return 0;
}
}

void TrainerClientAdapter::Start() {
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) return;
    logEvent("Trainer adapter initialization: held-flight/lookup-guard build");
    logEvent(installLookupGuard() ? "Observed v83 low-receiver lookup guard ready" : "Lookup guard unavailable: exact client bytes differ");
    flySupported = installFly();
    unlimitedSupported = readable(kUnlimitedAttack, 1)
        && *reinterpret_cast<unsigned char*>(kUnlimitedAttack) == 0x7E;
    rapidSupported = readable(kNoAttackDelay, sizeof(kNoAttackDelayOriginal))
        && std::memcmp(reinterpret_cast<void*>(kNoAttackDelay), kNoAttackDelayOriginal,
                       sizeof(kNoAttackDelayOriginal)) == 0;
    skillEffectsSupported = readable(kSkillEffect, sizeof(kSkillEffectOriginal))
        && std::memcmp(reinterpret_cast<void*>(kSkillEffect), kSkillEffectOriginal, sizeof(kSkillEffectOriginal)) == 0;
    fallThroughSupported = readable(kFallThrough, sizeof(kFallThroughOriginal))
        && std::memcmp(reinterpret_cast<void*>(kFallThrough), kFallThroughOriginal, sizeof(kFallThroughOriginal)) == 0;
    logEvent(flySupported ? "Mouse fly hook ready" : "Mouse fly hook unavailable: v83 site bytes differ");
    logEvent(unlimitedSupported ? "Unlimited Attack patch ready" : "Unlimited Attack patch unavailable: v83 site byte differs");
    logEvent(rapidSupported ? "Rapid Attack patch ready" : "Rapid Attack patch unavailable: v83 site bytes differ");
    initialized.store(true);
    HANDLE watchdog = CreateThread(nullptr, 0, leaseWatchdog, nullptr, 0, nullptr);
    if (!watchdog) { logEvent("Client control disabled: native watchdog could not start"); return; }
    CloseHandle(watchdog);
    HANDLE thread = CreateThread(nullptr, 0, pipeWorker, nullptr, 0, nullptr);
    if (thread) CloseHandle(thread);
    else logEvent("Trainer client adapter unavailable: pipe worker could not start");
}


void TrainerClientAdapter::Tick() {
    if (!initialized.load()) return;
    LONG rejected = InterlockedCompareExchange(&rejectedLookupReceivers, 0, 0);
    if (rejected != reportedLookupReceivers) {
        char message[128]{};
        std::snprintf(message, sizeof(message), "Rejected invalid v83 lookup receiver count=%ld; original fault site=00973744", rejected);
        logEvent(message); reportedLookupReceivers = rejected;
    }
    // A new physical field invalidates captured altitude. Keep the pipe attached.
    DWORD space = readable(0x00BEBFA0, sizeof(DWORD)) ? *reinterpret_cast<DWORD*>(0x00BEBFA0) : 0;
    if (space != physicalSpace) {
        if (flyEnabled || hoverEnabled || fallThroughEnabled) {
            InterlockedExchange(&flyEnabled, 0); InterlockedExchange(&flySteering, 0); InterlockedExchange(&hoverEnabled, 0);
            if (fallThroughSupported) setFallThrough(false);
            ++movementResets;
        }
        physicalSpace = space; lastSnapshotAt = 0; InterlockedExchange(&snapshotSeen, 0);
    }
    if (InterlockedExchange(&snapshotSeen, 0)) lastSnapshotAt = GetTickCount64();
    const auto last = lastContact.load();
    if (resetRequested.exchange(false) || (last && GetTickCount64() - last > 5000)) {
        const bool success = resetControls();
        lastContact.store(0);
        leaseResets.fetch_add(1);
        logEvent(success ? "Native lease/disconnect reset on game thread" : "Game-thread reset incomplete");
    }
    std::shared_ptr<PendingCommand> request;
    {
        std::lock_guard<std::mutex> guard(commandMutex);
        request.swap(pendingCommand);
    }
    if (request) {
        request->response = request->cancelled.load() ? "ERR CANCELLED" : executeCommand(request->text);
        SetEvent(request->done);
    }
    updateFlySteering();
    // Bound background work without changing simulation clocks or sending input.
    // A focus change restores the normal loop within at most 100 ms.
    if (cpuMode) {
        DWORD focused = 0; GetWindowThreadProcessId(GetForegroundWindow(), &focused);
        ULONGLONG now = GetTickCount64();
        if (focused != GetCurrentProcessId() && lastCpuFrame) {
            DWORD interval = 1000 / backgroundFps;
            if (now - lastCpuFrame < interval) Sleep(static_cast<DWORD>(interval - (now - lastCpuFrame)));
        }
        lastCpuFrame = GetTickCount64();
    }
}

#ifdef TRAINER_NATIVE_TEST
namespace {
DWORD testEax,testEbx,testEcx,testEdx,testEdi,testFlags;
DWORD testXEntry=0x009B6295,testYEntry=0x009B62F4;
BYTE testNativeAddressReserve[0xC00000];
__declspec(naked) void testContinuation() {
    __asm {
        mov [testEax], eax
        mov [testEbx], ebx
        mov [testEcx], ecx
        mov [testEdx], edx
        mov [testEdi], edi
        pushfd
        pop [testFlags]
        pop edi
        pop esi
        pop ebx
        pop ebp
        ret
    }
}
__declspec(naked) void __cdecl invokeX(DWORD*,DWORD,DWORD,DWORD) {
    __asm {
        push ebp
        mov ebp, esp
        push ebx
        push esi
        push edi
        mov ebx, [ebp+8]
        mov eax, [ebp+0Ch]
        xor esi, esi
        mov ecx, 12345678h
        mov edx, 23456789h
        test ebx, ebx
        stc
        jmp dword ptr [testXEntry]
    }
}
__declspec(naked) void __cdecl invokeY(DWORD*,DWORD,DWORD,DWORD) {
    __asm {
        push ebp
        mov ebp, esp
        push ebx
        push esi
        push edi
        mov edi, [ebp+8]
        mov eax, [ebp+0Ch]
        xor esi, esi
        mov ecx, 12345678h
        mov edx, 23456789h
        test edi, edi
        stc
        jmp dword ptr [testYEntry]
    }
}
}
int main() {
    // Execute the production naked hooks with actual optional output pointers,
    // native stack slots and registers. No game process is needed.
    // Reserve the native fixture addresses inside this test image before CRT
    // heap initialization; ordinary late VirtualAlloc can collide with its heap.
    if(reinterpret_cast<DWORD>(testNativeAddressReserve)>0x00920000
       ||reinterpret_cast<DWORD>(testNativeAddressReserve)+sizeof(testNativeAddressReserve)<0x00BF0000)return 1;
    void* globals=reinterpret_cast<void*>(0x00BE0000);
    void* fixture=reinterpret_cast<void*>(0x009B0000);DWORD protection=0;
    memset(globals,0,65536);
    if(!VirtualProtect(fixture,65536,PAGE_EXECUTE_READWRITE,&protection))return 4;
    memset(fixture,0x90,65536);
    memcpy(reinterpret_cast<void*>(0x009B6295),"\x74\x58",2);
    memcpy(reinterpret_cast<void*>(0x009B62F4),"\x74\x5E",2);
    memcpy(reinterpret_cast<void*>(kFlyX),kFlyXOriginal,5);
    memcpy(reinterpret_cast<void*>(kFlyY),kFlyYOriginal,5);
    const DWORD xForward=kFlyX-0x009B6297-5,yForward=kFlyY-0x009B62F6-5;
    *reinterpret_cast<BYTE*>(0x009B6297)=0xE9;memcpy(reinterpret_cast<void*>(0x009B6298),&xForward,4);
    *reinterpret_cast<BYTE*>(0x009B62F6)=0xE9;memcpy(reinterpret_cast<void*>(0x009B62F7),&yForward,4);
    if(!installFly()||*reinterpret_cast<BYTE*>(0x009B6296)!=0x56||*reinterpret_cast<BYTE*>(0x009B62F5)!=0x5C)return 5;
    kFlyXReturn=kFlyYReturn=reinterpret_cast<DWORD>(testContinuation);
    for(int i=0;i<10000;++i){
        DWORD output=0;DWORD* pointer=i%2?&output:nullptr;
        invokeX(pointer,0x3456789A,0x456789AB,0x56789ABC);
        if((pointer&&output!=0x3456789A)||testEax!=0x3456789A||testEdi!=0x456789AB
            ||testEcx!=0x12345678||testEdx!=0x23456789||!(testFlags&1))return 2;
        invokeY(pointer,0x3456789A,0x456789AB,0x56789ABC);
        if((pointer&&output!=0x3456789A)||testEax!=0x3456789A||testEbx!=0x56789ABC
            ||testEcx!=0x12345678||testEdx!=0x23456789||!(testFlags&1))return 3;
    }
    void* rapidFixture=reinterpret_cast<void*>(0x00920000);
    if(!VirtualProtect(rapidFixture,65536,PAGE_EXECUTE_READWRITE,&protection))return 6;
    memcpy(reinterpret_cast<void*>(kNoAttackDelay),kNoAttackDelayOriginal,10);rapidSupported=true;
    if(!setRapidAttack(true))return 7;
    const auto rapid=reinterpret_cast<int(__stdcall*)(int,int,int,int)>(kNoAttackDelay);
    for(int i=0;i<10000;++i)if(rapid(1,2,3,4)!=1)return 8;
    if(!setRapidAttack(false)||memcmp(reinterpret_cast<void*>(kNoAttackDelay),kNoAttackDelayOriginal,10))return 9;
    std::puts("PASS production fly installation + incoming native branches: 10000 X + 10000 Y calls, null/valid outputs, tail registers, flags, stack and values preserved; rapid actual x86 ret16 gate 10000 calls + exact OFF restoration");
    return 0;
}
#endif
