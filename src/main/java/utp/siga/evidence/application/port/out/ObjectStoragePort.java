package utp.siga.evidence.application.port.out;

import java.io.InputStream;

public interface ObjectStoragePort {
    void put(String objectKey, byte[] content, String contentType);
    InputStream get(String objectKey);
    void delete(String objectKey);
}
