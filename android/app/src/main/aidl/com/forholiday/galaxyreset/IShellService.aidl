package com.forholiday.galaxyreset;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
interface IShellService {
    Bundle execute(String command, int timeoutSeconds) = 1;
    void copyAsset(in ParcelFileDescriptor source, String destination) = 2;
    int uid() = 3;
    void destroy() = 16777114;
}
