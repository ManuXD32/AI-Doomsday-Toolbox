package com.example.llamadroid.harness;

import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBlockGuardOs;
import org.robolectric.shadows.ShadowLinux;

/** Host directory handles for the lifecycle fixture; Robolectric uses RandomAccessFile. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
public class HarnessDirectorySyncShadow extends ShadowLinux {
    private static final ConcurrentHashMap<FileDescriptor, FileChannel> DIRECTORIES =
            new ConcurrentHashMap<>();
    private static final AtomicInteger DIRECTORY_SYNCS = new AtomicInteger();

    public static int openDirectoryCount() {
        return DIRECTORIES.size();
    }

    public static int directorySyncCount() {
        return DIRECTORY_SYNCS.get();
    }

    public static void resetCounts() {
        if (!DIRECTORIES.isEmpty()) {
            throw new IllegalStateException("Directory descriptors leaked from a previous fixture");
        }
        DIRECTORY_SYNCS.set(0);
    }

    @Override
    @Implementation
    protected FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        Path directory = Path.of(path);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return super.open(path, flags, mode);
        }
        if (flags != OsConstants.O_RDONLY) {
            throw new ErrnoException("open", OsConstants.EINVAL);
        }
        try {
            FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ);
            FileDescriptor descriptor = new FileDescriptor();
            DIRECTORIES.put(descriptor, channel);
            return descriptor;
        } catch (IOException error) {
            throw new ErrnoException("open", OsConstants.EIO, error);
        }
    }

    @Implementation
    protected void fsync(FileDescriptor descriptor) throws ErrnoException {
        try {
            FileChannel directory = DIRECTORIES.get(descriptor);
            if (directory == null) {
                descriptor.sync();
            } else {
                directory.force(true);
                DIRECTORY_SYNCS.incrementAndGet();
            }
        } catch (IOException error) {
            throw new ErrnoException("fsync", OsConstants.EIO, error);
        }
    }

    @Implementation
    protected void close(FileDescriptor descriptor) throws ErrnoException {
        try {
            if (!closeDirectory(descriptor)) {
                new FileInputStream(descriptor).close();
            }
        } catch (IOException error) {
            throw new ErrnoException("close", OsConstants.EIO, error);
        }
    }

    private static boolean closeDirectory(FileDescriptor descriptor) throws IOException {
        FileChannel directory = DIRECTORIES.get(descriptor);
        if (directory == null) return false;
        directory.close();
        DIRECTORIES.remove(descriptor, directory);
        return true;
    }

    /** Robolectric's BlockGuard shadow otherwise consumes close without forwarding it. */
    @Implements(className = "libcore.io.BlockGuardOs", isInAndroidSdk = false)
    public static class CloseBridge extends ShadowBlockGuardOs {
        @Override
        @Implementation
        protected void close(FileDescriptor descriptor) throws ErrnoException {
            try {
                if (!closeDirectory(descriptor)) super.close(descriptor);
            } catch (IOException error) {
                throw new ErrnoException("close", OsConstants.EIO, error);
            }
        }
    }
}
