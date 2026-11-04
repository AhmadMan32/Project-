package com.ahmadman.disableheadphone;

/** Runs inside the Shizuku (ADB shell) process. See ForceUseService. */
interface IForceUseService {

    /** Shizuku calls this when the service is unbound; the implementation exits the process. */
    void destroy() = 16777114;

    /** AudioSystem.setForceUse(FOR_MEDIA, speaker ? FORCE_SPEAKER : FORCE_NONE). 0 = success. */
    int setForceMedia(boolean speaker) = 1;

    /** AudioSystem.getForceUse(FOR_MEDIA), or a negative value on error. */
    int getForceMedia() = 2;

    /** Uid the helper runs as (2000 = shell, 0 = root). */
    int getUid() = 3;
}
