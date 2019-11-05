package io.github.alexarchambault.jsonrpc.demo;

// from https://github.com/plokhotnyuk/jsoniter-scala/commit/e089f06c2d8b4bdb87a6874e17bf716e8608b117

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "scala.runtime.Statics")
final class Target_scala_runtime_Statics {

    @Substitute
    public static void releaseFence() {
        UnsafeUtils.UNSAFE.storeFence();
    }
}
