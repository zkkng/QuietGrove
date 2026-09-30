#pragma once

namespace TrainerClientAdapter {
    // Called after the v83 client has unpacked and the base plugin has finished.
    void Start();
    // Main game thread: bounded command queue and lease reset before input.
    void Tick();
}
