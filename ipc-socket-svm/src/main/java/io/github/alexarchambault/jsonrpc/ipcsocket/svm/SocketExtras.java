package io.github.alexarchambault.jsonrpc.ipcsocket.svm;

import com.oracle.svm.core.posix.headers.PosixDirectives;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.struct.*;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;

@CContext(PosixDirectives.class)
class SocketExtras {

    // Same as com.oracle.svm.core.posix.headers.Socket.sockaddr, but with setters.
    @CStruct(addStructKeyword = true)
    public interface sockaddr extends PointerBase {

        @Platforms(Platform.DARWIN.class)
        @CField("sa_len")
        @AllowWideningCast
        int sa_len();

        @CField("sa_family")
        @AllowWideningCast
        int sa_family();

        /** Address data. */
        @CFieldAddress("sa_data")
        CCharPointer sa_data();


        @Platforms(Platform.DARWIN.class)
        @CField("sa_len")
        @AllowNarrowingCast
        void setLen(int sa_len);

        @CField("sa_family")
        @AllowNarrowingCast
        void setFamily(int sa_family);
    }
}
