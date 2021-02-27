package com.chenhaiyang.plugin.mybatis.sensitive.interceptor;

import com.chenhaiyang.plugin.mybatis.sensitive.annotation.*;
import com.chenhaiyang.plugin.mybatis.sensitive.type.SensitiveType;
import com.chenhaiyang.plugin.mybatis.sensitive.type.SensitiveTypeRegisty;
import com.chenhaiyang.plugin.mybatis.sensitive.utils.JsonUtils;
import com.chenhaiyang.plugin.mybatis.sensitive.utils.PluginUtils;
import com.chenhaiyang.plugin.mybatis.sensitive.Encrypt;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.*;

/**
 * 拦截写请求的插件。插件生效仅支持预编译的sql
 *
 * 实现原理：
 * 1，拦截mybatis的StatementHandler 对读写请求进行脱敏和字段的加密。
 * 2，拦截mybatis的ResultSetHandler，对读请求的响应进行加密字段的解密赋值。
 *
 * https://blog.csdn.net/lsqingfeng/article/details/113173379?utm_medium=distribute.pc_relevant.none-task-blog-BlogCommendFromMachineLearnPai2-2.control&dist_request_id=41b42654-8a1a-496d-9734-325690cc4a34&depth_1-utm_source=distribute.pc_relevant.none-task-blog-BlogCommendFromMachineLearnPai2-2.control
 * 接下来就是拦截器的写法了，mybatis给我们提供了对应的插件扩展，对于mybatis-plus同样适用。
 * mybatis在插入的时候有一个方法叫做setParameter, 会对参数做设置， 查询的时候有一个方法叫做handleResultSet,
 * 会对结果做操作，我们只需要拦截这两个请求，设置参数的时候，加密敏感字段；操作结果的时候，解密敏感字段即可。
 * @author ;
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
})
@Slf4j
public class SensitiveAndEncryptWriteInterceptor implements Interceptor {

    private static final String MAPPEDSTATEMENT="delegate.mappedStatement";
    private static final String BOUND_SQL="delegate.boundSql";

    private Encrypt encrypt;

    public SensitiveAndEncryptWriteInterceptor(Encrypt encrypt) {
        Objects.requireNonNull(encrypt,"encrypt should not be null!");
        this.encrypt = encrypt;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {

        StatementHandler statementHandler = PluginUtils.realTarget(invocation.getTarget());
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        MappedStatement mappedStatement = (MappedStatement)metaObject.getValue(MAPPEDSTATEMENT);
        SqlCommandType commandType = mappedStatement.getSqlCommandType();

        BoundSql boundSql = (BoundSql)metaObject.getValue(BOUND_SQL);
        Object params = boundSql.getParameterObject();
        if(params instanceof Map){
            return invocation.proceed();
        }
        SensitiveEncryptEnabled sensitiveEncryptEnabled = params != null ? params.getClass().getAnnotation(SensitiveEncryptEnabled.class) : null;
        if(sensitiveEncryptEnabled != null && sensitiveEncryptEnabled.value()){
            handleParameters(mappedStatement.getConfiguration(), boundSql,params,commandType);
        }
        return invocation.proceed();
    }

    private void handleParameters(Configuration configuration, BoundSql boundSql,Object param,SqlCommandType commandType) throws Exception {

        Map<String, Object> newValues = new HashMap<>(16);
        MetaObject metaObject = configuration.newMetaObject(param);

        for (Field field : param.getClass().getDeclaredFields()) {

            Object value = metaObject.getValue(field.getName());
            Object newValue = value;
            if(value instanceof CharSequence){
                newValue = handleEncryptField(field,newValue);
                if(isWriteCommand(commandType) && !SensitiveTypeRegisty.alreadyBeSentisived(newValue)) {
                    newValue = handleSensitiveField(field, newValue);
                    newValue = handleSensitiveJSONField(field, newValue);
                }
            }
            if(value!=null && newValue!=null && !value.equals(newValue)) {
                newValues.put(field.getName(), newValue);
            }
        }
        for (Map.Entry<String, Object> entry: newValues.entrySet()) {
            boundSql.setAdditionalParameter(entry.getKey(),entry.getValue());
        }

    }

    private boolean isWriteCommand(SqlCommandType commandType) {
        return SqlCommandType.UPDATE.equals(commandType) || SqlCommandType.INSERT.equals(commandType);
    }

    private Object handleEncryptField(Field field, Object value) {

        EncryptField encryptField = field.getAnnotation(EncryptField.class);
        Object newValue = value;
        if (encryptField != null && value != null) {
            newValue = encrypt.encrypt(value.toString());
        }
        return newValue;
    }

    private Object handleSensitiveField(Field field, Object value) {
        SensitiveField sensitiveField = field.getAnnotation(SensitiveField.class);
        Object newValue = value;
        if (sensitiveField != null && value != null) {
            newValue = SensitiveTypeRegisty.get(sensitiveField.value()).handle(value);
        }
        return newValue;
    }
    private Object handleSensitiveJSONField(Field field, Object value) {
        SensitiveJSONField sensitiveJSONField = field.getAnnotation(SensitiveJSONField.class);
        Object newValue = value;
        if (sensitiveJSONField != null && value != null) {
            newValue = processJsonField(newValue,sensitiveJSONField);
        }
        return newValue;
    }

    /**
     * 在json中进行脱敏
     * @param newValue new
     * @param sensitiveJSONField 脱敏的字段
     * @return json
     */
    private Object processJsonField(Object newValue,SensitiveJSONField sensitiveJSONField) {

        try{
            Map<String,Object> map = JsonUtils.parseToObjectMap(newValue.toString());
            SensitiveJSONFieldKey[] keys =sensitiveJSONField.sensitivelist();
            for(SensitiveJSONFieldKey jsonFieldKey :keys){
                String key = jsonFieldKey.key();
                SensitiveType sensitiveType = jsonFieldKey.type();
                Object oldData = map.get(key);
                if(oldData!=null){
                    String newData = SensitiveTypeRegisty.get(sensitiveType).handle(oldData);
                    map.put(key,newData);
                }
            }
            return JsonUtils.parseMaptoJSONString(map);
        }catch (Throwable e){
            //失败以后返回默认值
            log.error("脱敏json串时失败，cause : {}",e.getMessage(),e);
            return newValue;
        }
    }

    @Override
    public Object plugin(Object o) {
        return Plugin.wrap(o, this);
    }

    @Override
    public void setProperties(Properties properties) {
        //do nothing
    }
}
