package io.github.alexarchambault.jsonrpc.ipcsocket.svm;

import java.io.IOException;

import com.oracle.svm.core.posix.headers.Unistd;

/**
 * Encapsulates a file descriptor plus a reference count to ensure close requests
 * only close the file descriptor once the last reference to the file descriptor
 * is released.
 *
 * If not explicitly closed, the file descriptor will be closed when
 * this object is finalized.
 */
public class ReferenceCountedFileDescriptor {
  private int fd;
  private int fdRefCount;
  private boolean closePending;

  public ReferenceCountedFileDescriptor(int fd) {
    this.fd = fd;
    this.fdRefCount = 0;
    this.closePending = false;
  }

  protected void finalize() throws IOException {
    close();
  }

  public synchronized int acquire() {
    fdRefCount++;
    return fd;
  }

  public synchronized void release() throws IOException {
    fdRefCount--;
    if (fdRefCount == 0 && closePending && fd != -1) {
      doClose();
    }
  }

  public synchronized void close() throws IOException {
    if (fd == -1 || closePending) {
      return;
    }

    if (fdRefCount == 0) {
      doClose();
    } else {
      // Another thread has the FD. We'll close it when they release the reference.
      closePending = true;
    }
  }

  private void doClose() throws IOException {
    int ret = Unistd.close(fd);
    ErrnoException.checkErrno(ret);
    fd = -1;
  }
}
