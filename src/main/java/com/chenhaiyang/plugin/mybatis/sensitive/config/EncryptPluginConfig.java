package com.chenhaiyang.plugin.mybatis.sensitive.config;

import com.chenhaiyang.plugin.mybatis.sensitive.Encrypt;
import com.chenhaiyang.plugin.mybatis.sensitive.encrypt.AesSupport;
import com.chenhaiyang.plugin.mybatis.sensitive.interceptor.DecryptReadInterceptor;
import com.chenhaiyang.plugin.mybatis.sensitive.interceptor.SensitiveAndEncryptWriteInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 插件配置
 */
@Configuration
public class EncryptPluginConfig {

    /**
     * 注册 AES加解密类
     *
     * @return AesSupport
     * @throws Exception
     */
    @Bean
    @Order(-1)
    public Encrypt encryptor() throws Exception {
        return new AesSupport("1870577f29b17d6787782f35998c4a79");
    }

    /**
     * 注册：
     * 1、拦截写请求的插件
     * 2、对响应结果进行拦截处理的插件
     * @return ConfigurationCustomizer
     * @throws Exception
     */
//    @Bean
////    @Order(-2)
//    @ConditionalOnMissingBean(ConfigurationCustomizer.class)
//    public ConfigurationCustomizer configurationCustomizer() throws Exception {
//        DecryptReadInterceptor decryptReadInterceptor = new DecryptReadInterceptor(encryptor());
//        SensitiveAndEncryptWriteInterceptor sensitiveAndEncryptWriteInterceptor = new SensitiveAndEncryptWriteInterceptor(encryptor());
//
//        return (configuration) -> {
//            configuration.addInterceptor(decryptReadInterceptor);
//            configuration.addInterceptor(sensitiveAndEncryptWriteInterceptor);
//        };
//    }

    /**
     * 注册 拦截写请求的插件，插件生效仅支持预编译的sql
     */
    @Bean
    @Order(-2)
    public SensitiveAndEncryptWriteInterceptor sensitiveAndEncryptWriteInterceptor() throws Exception {
        return new SensitiveAndEncryptWriteInterceptor(encryptor());
    }

    /**
     * 注册 对响应结果进行拦截处理的插件，对需要解密的字段进行解密
     */
    @Bean
    @Order(-2)
    public DecryptReadInterceptor decryptReadInterceptor() throws Exception {
        return new DecryptReadInterceptor(encryptor());
    }
}
