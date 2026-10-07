package io.devreload.mybatis;

/**
 * mapper XML을 리로드할 수 없을 때 던진다. 이 예외가 던져진 시점에는 이전 mapper 상태가 이미 복원되어 있다.
 */
public class MapperReloadException extends RuntimeException {

    public MapperReloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
