/*

 Copyright 2004-2015, Martian Software, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */
package io.github.alexarchambault.jsonrpc.ipcsocket.svm;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Unistd;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

/**
 * Implements a {@link ServerSocket} which binds to a local Unix domain socket
 * and returns instances of {@link UnixDomainSocket} from
 * {@link #accept()}.
 */
public class UnixDomainServerSocket extends ServerSocket {
  private static final int DEFAULT_BACKLOG = 50;

  // We use an AtomicInteger to prevent a race in this situation which
  // could happen if fd were just an int:
  //
  // Thread 1 -> UnixDomainServerSocket.accept()
  //          -> lock this
  //          -> check isBound and isClosed
  //          -> unlock this
  //          -> descheduled while still in method
  // Thread 2 -> UnixDomainServerSocket.close()
  //          -> lock this
  //          -> check isClosed
  //          -> UnixDomainSocketLibrary.close(fd)
  //          -> now fd is invalid
  //          -> unlock this
  // Thread 1 -> re-scheduled while still in method
  //          -> UnixDomainSocketLibrary.accept(fd, which is invalid and maybe re-used)
  //
  // By using an AtomicInteger, we'll set this to -1 after it's closed, which
  // will cause the accept() call above to cleanly fail instead of possibly
  // being called on an unrelated fd (which may or may not fail).
  private final AtomicInteger fd;

  private final int backlog;
  private boolean isBound;
  private boolean isClosed;

  public static class UnixDomainServerSocketAddress extends SocketAddress {
    private final String path;

    public UnixDomainServerSocketAddress(String path) {
      this.path = path;
    }

    public String getPath() {
      return path;
    }
  }

  /**
   * Constructs an unbound Unix domain server socket.
   */
  public UnixDomainServerSocket() throws IOException {
    this(DEFAULT_BACKLOG, null);
  }

  /**
   * Constructs an unbound Unix domain server socket with the specified listen backlog.
   */
  public UnixDomainServerSocket(int backlog) throws IOException {
    this(backlog, null);
  }

  /**
   * Constructs and binds a Unix domain server socket to the specified path.
   */
  public UnixDomainServerSocket(String path) throws IOException {
    this(DEFAULT_BACKLOG, path);
  }

  /**
   * Constructs and binds a Unix domain server socket to the specified path
   * with the specified listen backlog.
   */
  public UnixDomainServerSocket(int backlog, String path) throws IOException {
    fd = new AtomicInteger(
            Socket.socket(
                    Socket.PF_LOCAL(),
                    Socket.SOCK_STREAM(),
                    0));
    if (fd.get() == -1) {
      ErrnoException.errno();
    }
    this.backlog = backlog;
    if (path != null) {
      bind(new UnixDomainServerSocketAddress(path));
    }
  }


  public synchronized void bind(SocketAddress endpoint) throws IOException {
    if (!(endpoint instanceof UnixDomainServerSocketAddress)) {
      throw new IllegalArgumentException(
          "endpoint must be an instance of UnixDomainServerSocketAddress");
    }
    if (isBound) {
      throw new IllegalStateException("Socket is already bound");
    }
    if (isClosed) {
      throw new IllegalStateException("Socket is already closed");
    }
    UnixDomainServerSocketAddress unEndpoint = (UnixDomainServerSocketAddress) endpoint;
    String path0 = unEndpoint.getPath();
    int len = SizeOf.get(Socket.sockaddr.class) + path0.length() + 1;
    SocketExtras.sockaddr address0 = UnmanagedMemory.calloc(len);
    try {
      address0.setFamily(Socket.AF_LOCAL());
      address0.setLen(path0.length() + 1);
      CTypeConversion.toCString(path0, address0.sa_data(), WordFactory.unsigned(path0.length() + 1));
      int socketFd = fd.get();
      int bindRet = Socket.bind(socketFd, (Socket.sockaddr) address0, len);
      ErrnoException.checkErrno(bindRet);
      int listenRet = Socket.listen(socketFd, backlog);
      ErrnoException.checkErrno(listenRet);
      isBound = true;
    } finally {
      if (address0.isNonNull())
        UnmanagedMemory.free(address0);
    }
  }

  public java.net.Socket accept() throws IOException {
    // We explicitly do not make this method synchronized, since the
    // call to UnixDomainSocketLibrary.accept() will block
    // indefinitely, causing another thread's call to close() to deadlock.
    synchronized (this) {
      if (!isBound) {
        throw new IllegalStateException("Socket is not bound");
      }
      if (isClosed) {
        throw new IllegalStateException("Socket is already closed");
      }
    }
    Socket.sockaddr sockaddrUn0 = StackValue.get(Socket.sockaddr.class);
    CIntPointer addressLen0 = StackValue.get(CIntPointer.class);
    addressLen0.write(SizeOf.get(Socket.sockaddr.class));
    int clientFd = Socket.accept(fd.get(), sockaddrUn0, addressLen0);
    if (clientFd == -1)
      ErrnoException.errno();
    return new UnixDomainSocket(clientFd);
  }

  public synchronized void close() throws IOException {
    if (isClosed) {
      throw new IllegalStateException("Socket is already closed");
    }
    // Ensure any pending call to accept() fails.
    int ret = Unistd.close(fd.getAndSet(-1));
    ErrnoException.checkErrno(ret);
    isClosed = true;
  }
}
