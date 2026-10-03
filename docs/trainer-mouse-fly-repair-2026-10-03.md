# Mouse Fly repair — October 3

The owner reported Mouse Fly stopped working after the October 3 trainer log showed repeated adapter connection timeouts and a map transition movement reset.

## Confirmed source defects and changes

- Repeated INJECT HAX previously overwrote the adapter reference with a second connection without disposing the first. The native pipe explicitly permits one instance. The trainer now pings and reuses the existing connection, or closes a failed connection before attempting replacement.
- An unavailable adapter previously stayed unavailable until another manual attachment. A paired trainer now retries during heartbeat and only enables controls after connection succeeds.
- Native field changes correctly clear steering and altitude. The trainer now restores the selected Mouse Fly mode after an acknowledged reset, only with the held-ALT configuration capability. Hover and Fall Through remain reset. Epoch/connection checks and a fresh selection check prevent restoration after ALL OFF or a user toggle.
- A replaced connection clears native UI selections so they do not advertise settings lost on disconnect.
- Active-state text now identifies the visible ALL OFF button rather than advertising an unavailable panic hotkey.

The native DLL and game executable are unchanged. No server release is needed.

## Verification and deployment status

Critical compilation succeeds. The initial candidate passed the existing codec, shortcut and preset self-tests. After the native selection reset correction, the final candidate compiled with SHA256 `287EE41ADB700F3985A306098F5732971CBE7038FD67E85C16F8B5139EB4A77F`.

Windows Application Control blocked execution of the final candidate and the separate `AdapterConnectionTest.exe`. The regression harness compiles and exercises 20 repeated attaches against a local named pipe, verifying connection identity, no OFF command during reuse, and OFF on disposal; it has **not run**. No security policy was changed or bypassed.

Final candidate: `tools/solo-trainer/critical-mouse-fly/SoloTrainer.exe` in the trainer-social worktree. It is **not installed**. The existing bin executable is preserved. Two existing SoloTrainer processes (28172 and 34892) were observed; neither was stopped because a runnable replacement was unavailable.

Actual flight, recovery after a map transition, and repeated INJECT HAX remain gameplay checks after a runnable build is available. This does not establish crash stability or rapid attack speed.
