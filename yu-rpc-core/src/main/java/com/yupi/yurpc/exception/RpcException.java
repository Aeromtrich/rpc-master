package com.yupi.yurpc.exception;

/**
 * 自定义异常类
 *
 * @author Aeromtrich
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message);
    }

}
