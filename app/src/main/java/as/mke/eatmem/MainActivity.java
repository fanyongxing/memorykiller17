package as.mke.eatmem;

import androidx.annotation.Keep;

@Keep
public class MainActivity {
    static {
        System.loadLibrary("hello-jni");
    }

    @Keep
    public native void native_eatmf();
}
