package com.yupi.yurpc.server;

/**
 * HTTP 服务器接口
 *
 * @author Aeromtrich
 */
public interface HttpServer {

    /**
     * 启动服务器
     *
     * @param port
     */
    void doStart(int port);
}
