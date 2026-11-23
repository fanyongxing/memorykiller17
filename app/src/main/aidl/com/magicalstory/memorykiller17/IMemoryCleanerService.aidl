package com.magicalstory.memorykiller17;

interface IMemoryCleanerService {
    void destroy() = 16777114;

    String prepareEatAndWatch(int uid, int hostPid, boolean returnHomeOnExit) = 1;

    String restoreMemoryLimiter() = 2;

    String getMemoryLimiterStatus() = 3;

    String runSafeClean() = 4;
}
