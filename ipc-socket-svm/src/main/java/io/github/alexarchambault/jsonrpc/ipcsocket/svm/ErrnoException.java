package io.github.alexarchambault.jsonrpc.ipcsocket.svm;

import java.io.IOException;
import java.io.FileNotFoundException;

import com.oracle.svm.core.headers.Errno;
import com.oracle.svm.core.posix.headers.Unistd;
import org.graalvm.nativeimage.c.type.CTypeConversion;

public class ErrnoException extends IOException {

  private final int errno0;

  public ErrnoException(int errno) {
    this(errno, null);
  }

  public ErrnoException(int errno, Throwable cause) {
    super("Errno " + CTypeConversion.toJavaString(Errno.strerror(errno)) + " (" + errno + ")", cause);
    errno0 = errno;
  }

  public int value() {
    return errno0;
  }

  public static void errno() throws IOException {
    errno(null);
  }

  public static void errno(String path) throws IOException {
    int n = Errno.errno();
    Throwable cause = null;
    if (n == Errno.ENOENT() || n == Errno.ENOTDIR())
      cause = new FileNotFoundException(path);
    throw new ErrnoException(n, cause);
  }

  public static void checkErrno(int ret) throws IOException {
    checkErrno(ret, -1, null);
  }

  public static void checkErrno(int ret, String path) throws IOException {
    checkErrno(ret, -1, path);
  }

  public static void checkErrno(int ret, int fd, String path) throws IOException {
    if (ret != 0) {
      try {
        errno(path);
      } finally {
        if (fd != -1) {
          Unistd.close(fd);
        }
      }
    }
  }

}
