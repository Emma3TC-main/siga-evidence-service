package utp.siga.evidence.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import utp.siga.evidence.application.exception.EvidenceException;
import utp.siga.evidence.application.port.out.ObjectStoragePort;

@Component
public class MinioObjectStorageAdapter implements ObjectStoragePort {
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorageAdapter(MinioClient client, @Value("${evidence.storage.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @PostConstruct
    void initializePrivateBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            try {
                String policy = client.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
                if (policy != null && !policy.isBlank()) {
                    throw new IllegalStateException("El bucket Evidence debe ser privado y no tener una política pública");
                }
            } catch (ErrorResponseException e) {
                if (!"NoSuchBucketPolicy".equals(e.errorResponse().code())) throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo validar el bucket privado de Evidence", e);
        }
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try {
            if (exists(objectKey)) {
                throw new EvidenceException(HttpStatus.CONFLICT, "STORAGE_OBJECT_EXISTS",
                        "Ya existe el objeto reservado para esta evidencia");
            }
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .contentType(contentType).stream(new ByteArrayInputStream(content), content.length, -1).build());
        } catch (EvidenceException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable("No se pudo almacenar la evidencia", e);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw unavailable("No se pudo recuperar el contenido de la evidencia", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw unavailable("No se pudo compensar el objeto de evidencia", e);
        }
    }

    private boolean exists(String objectKey) throws Exception {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NoSuchObject".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }

    private static EvidenceException unavailable(String detail, Throwable cause) {
        return new EvidenceException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", detail, cause);
    }
}
