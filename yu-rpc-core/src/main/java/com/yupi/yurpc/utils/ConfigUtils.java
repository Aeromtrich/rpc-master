package com.yupi.yurpc.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.setting.dialect.Props;

/**
 * 配置工具类
 *
 * @author Aeromtrich
 */
public class ConfigUtils {

    /**
     * 加载配置对象
     *
     * @param tClass 目标 Bean 的 Class 对象 例如 RpcConfig.class）
     * @param prefix  配置前缀（例如 "rpc"）
     * @param <T>
     * @return
     */
    public static <T> T loadConfig(Class<T> tClass, String prefix) {
        return loadConfig(tClass, prefix, "");
    }

    /**
     * 加载配置对象，支持区分环境
     *
     * @param tClass
     * @param prefix
     * @param environment
     * @param <T>
     * @return
     */
    public static <T> T loadConfig(Class<T> tClass, String prefix, String environment) {
        StringBuilder configFileBuilder = new StringBuilder("application");
        if (StrUtil.isNotBlank(environment)) {
            configFileBuilder.append("-").append(environment);
        }
        configFileBuilder.append(".properties");
        Props props = new Props(configFileBuilder.toString());
        // 2. 去掉 "rpc." 前缀，得到属性名
        return props.toBean(tClass, prefix); // 将带有指定前缀的配置项转换为 Java 对象 例如 RpcConfig
    }
}
