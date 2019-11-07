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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.oracle.svm.core.os.IsDefined;
import com.oracle.svm.core.posix.headers.Socket;
import com.oracle.svm.core.posix.headers.Unistd;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * Implements a {@link java.net.Socket} backed by a native Unix domain socket.
 *
 * Instances of this class always return {@code null} for
 * {@link java.net.Socket#getInetAddress()}, {@link java.net.Socket#getLocalAddress()},
 * {@link java.net.Socket#getLocalSocketAddress()}, {@link java.net.Socket#getRemoteSocketAddress()}.
 */
public class UnixDomainSocket extends java.net.Socket {
  private final InputStream is;
  private final OutputStream os;
  private final ReferenceCountedFileDescriptor fd;

  /**
   * Creates a Unix domain socket backed by a file path.
   */
  public UnixDomainSocket() throws IOException {
    int fd = Socket.socket(Socket.PF_LOCAL(), Socket.SOCK_STREAM(), 0);
    if (fd == -1) {
      ErrnoException.errno();
    }
    this.is = new UnixDomainSocketInputStream();
    this.os = new UnixDomainSocketOutputStream();
    this.fd = new ReferenceCountedFileDescriptor(fd);
  }

  private static boolean addOne() { return Boolean.getBoolean("jsonrpc.posix.sockaddr.add-one-to-length"); }
  private static boolean printLenStats() { return Boolean.getBoolean("jsonrpc.posix.sockaddr.print-len-stats"); }
  private static boolean printSockAddr() { return Boolean.getBoolean("jsonrpc.posix.sockaddr.print-sockaddr"); }

  @Override
  public void connect(SocketAddress endpoint) throws IOException {
    super.connect(endpoint);
  }

  static final String HEXES = "0123456789ABCDEF";

  @Platforms(Platform.DARWIN.class)
  private void maybeSetLen(SocketExtras.sockaddr address, int len) {
    address.setLen(len);
  }

  public void connect(String path) throws IOException {
    int extra;
    if (addOne())
      extra = 1;
    else
      extra = 0;
    // Should be 2 on OS X, and 1 on Linux, but actually gives 16 on OS X…
    int sockAddrLen = SizeOf.get(Socket.sockaddr.class);
    int len = sockAddrLen + path.length() + extra;
    if (printLenStats()) {
      System.err.println("sockAddrLen=" + sockAddrLen);
      System.err.println("path.length=" + path.length());
      System.err.println("extra=" + extra);
    }
    SocketExtras.sockaddr address0 = UnmanagedMemory.calloc(len);
    try {
      address0.setFamily(Socket.AF_LOCAL());
      if (IsDefined.isDarwin()) {
        // FIXME Better length in case of non ascii chars?
        maybeSetLen(address0, path.length() + extra);
      }
      CTypeConversion.toCString(path, address0.sa_data(),  WordFactory.unsigned(path.length() + extra));

      if (printSockAddr()) {
        CCharPointer p = (CCharPointer) address0;
        // https://stackoverflow.com/a/9655224/3714539
        final StringBuilder hex = new StringBuilder( 2 * len);
        for (int i = 0; i < len; i++) {
          byte b = p.read(i);
          hex.append(HEXES.charAt((b & 0xF0) >> 4))
                  .append(HEXES.charAt((b & 0x0F)));
        }
        System.err.println("Raw sockaddr: " + hex.toString());
      }

      try {
        int socketFd = fd.acquire();
        int err = Socket.connect(socketFd, (Socket.sockaddr) address0, len);
        ErrnoException.checkErrno(err, path);
      } finally {
        fd.release();
      }
    } finally {
      if (address0.isNonNull())
        UnmanagedMemory.free(address0);
    }
  }

  /**
   * Creates a Unix domain socket backed by a native file descriptor.
   */
  public UnixDomainSocket(int fd) {
    this.is = new UnixDomainSocketInputStream();
    this.os = new UnixDomainSocketOutputStream();
    this.fd = new ReferenceCountedFileDescriptor(fd);
  }

  public InputStream getInputStream() {
    return is;
  }

  public OutputStream getOutputStream() {
    return os;
  }

  public void shutdownInput() throws IOException {
    doShutdown(Socket.SHUT_RD());
  }

  public void shutdownOutput() throws IOException {
    doShutdown(Socket.SHUT_WR());
  }

  private void doShutdown(int how) throws IOException {
    try {
      int socketFd = fd.acquire();
      if (socketFd != -1) {
        int ret = Socket.shutdown(socketFd, how);
        ErrnoException.checkErrno(ret);
      }
    } finally {
      fd.release();
    }
  }

  public void close() throws IOException {
    super.close();
    // This might not close the FD right away. In case we are about
    // to read or write on another thread, it will delay the close
    // until the read or write completes, to prevent the FD from
    // being re-used for a different purpose and the other thread
    // reading from a different FD.
    fd.close();
  }

  private class UnixDomainSocketInputStream extends InputStream {
    public int read() throws IOException {
      CCharPointer buf0 = StackValue.get(1);
      int result;
      if (doRead(buf0, WordFactory.unsigned(1)) == 0) {
        result = -1;
      } else {
        // Make sure to & with 0xFF to avoid sign extension
        result = 0xFF & buf0.read();
      }
      return result;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      CCharPointer buf0 = UnmanagedMemory.calloc(len);
      try {
        int result = doRead(buf0, WordFactory.unsigned(len));
        ByteBuffer buf = CTypeConversion.asByteBuffer(buf0, len);
        buf.get(b, off, result);
        if (result == 0) {
          result = -1;
        }
        return result;
      } finally {
        if (buf0.isNonNull())
          UnmanagedMemory.free(buf0);
      }
    }

    private int doRead(PointerBase buf, UnsignedWord size) throws IOException {
      try {
        int socketFd = fd.acquire();
        if (socketFd == -1) {
          return -1;
        }
        int ret = (int) Unistd.read(socketFd, buf, size).rawValue();
        if (ret == -1)
          ErrnoException.errno();
        return ret;
      } finally {
        fd.release();
      }
    }
  }

  private class UnixDomainSocketOutputStream extends OutputStream {

    public void write(int b) throws IOException {
      CCharPointer buf = StackValue.get(1);
      buf.write((byte) (0xFF & b));
      doWrite(buf, WordFactory.unsigned(1));
    }

    public void write(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return;
      }
      CCharPointer buf = UnmanagedMemory.calloc(len);
      try {
        ByteBuffer buf0 = CTypeConversion.asByteBuffer(buf, len);
        buf0.put(b, off, len);
        doWrite(buf, WordFactory.unsigned(len));
      } finally {
        if (buf.isNonNull())
          UnmanagedMemory.free(buf);
      }
    }

    private void doWrite(PointerBase buf, UnsignedWord len) throws IOException {
      try {
        int socketFd = fd.acquire();
        if (socketFd == -1) {
          return;
        }
        int ret = (int) Unistd.write(socketFd, buf, len).rawValue();
        if (ret == -1)
          ErrnoException.errno();
        if (ret != (int) len.rawValue()) {
          // This shouldn't happen with standard blocking Unix domain sockets.
          throw new IOException("Could not write " + len.rawValue() + " bytes as requested " +
                                "(wrote " + ret + " bytes instead)");
        }
      } finally {
        fd.release();
      }
    }
  }

}
