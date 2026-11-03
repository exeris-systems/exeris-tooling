package eu.exeris.e2e.codegen;

import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRange;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link BlobStore} over a map, for driving a generated handler without a blob driver. It keeps
 * the SPI's upload contract — the declared length is the length committed, and an object becomes
 * visible only on {@code commit()} — and knows nothing of tenants or signed URLs.
 */
final class InMemoryBlobStore implements BlobStore {

    /** One stored object. */
    record Stored(byte[] bytes, String contentType) {
    }

    private final Map<BlobRef, Stored> objects = new LinkedHashMap<>();

    /** The stored objects, by reference. */
    Map<BlobRef, Stored> objects() {
        return objects;
    }

    @Override
    public BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType) {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        return new BlobUploadHandle() {
            @Override
            public void write(MemorySegment source, int length) {
                written.writeBytes(source.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE));
            }

            @Override
            public BlobMetadata commit() {
                if (written.size() != contentLength) {
                    throw new IllegalStateException("wrote " + written.size() + " of " + contentLength + " bytes");
                }
                String type = contentType != null ? contentType : BlobMetadata.DEFAULT_CONTENT_TYPE;
                objects.put(ref, new Stored(written.toByteArray(), type));
                return new BlobMetadata(ref, contentLength, type);
            }

            @Override
            public void close() {
                // nothing to abort: an uncommitted upload was never visible
            }
        };
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref) {
        Stored stored = objects.get(ref);
        if (stored == null) {
            throw new IllegalStateException("no object " + ref);
        }
        return new BlobDownloadHandle() {
            private int offset;

            @Override
            public BlobMetadata metadata() {
                return new BlobMetadata(ref, stored.bytes().length, stored.contentType());
            }

            @Override
            public int read(MemorySegment target, int maxBytes) {
                if (maxBytes <= 0) {
                    return 0;
                }
                if (offset == stored.bytes().length) {
                    return -1;
                }
                // At most 3 bytes a call, so the handler's read loop is exercised.
                int n = Math.min(Math.min(maxBytes, 3), stored.bytes().length - offset);
                MemorySegment.copy(stored.bytes(), offset, target, ValueLayout.JAVA_BYTE, 0, n);
                offset += n;
                return n;
            }

            @Override
            public void close() {
                // reads hold nothing to release
            }
        };
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref, BlobRange range) {
        throw new UnsupportedOperationException("ranged reads are not used by the generated handler");
    }

    @Override
    public Optional<BlobMetadata> stat(BlobRef ref) {
        Stored stored = objects.get(ref);
        return stored == null ? Optional.empty()
                : Optional.of(new BlobMetadata(ref, stored.bytes().length, stored.contentType()));
    }

    @Override
    public boolean delete(BlobRef ref) {
        return objects.remove(ref) != null;
    }

    @Override
    public Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public void close() {
        objects.clear();
    }
}
