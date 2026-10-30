package utp.siga.evidence.application.service;

public record ValidatedFile(byte[] bytes, String filename, String contentType, String extension, String sha256) {
    public long size() { return bytes.length; }
}
