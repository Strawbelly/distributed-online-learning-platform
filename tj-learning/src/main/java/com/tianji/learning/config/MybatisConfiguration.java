package com.tianji.learning.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.tianji.learning.utils.TableInfoContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class MybatisConfiguration {

    @Bean
    public DynamicTableNameInnerInterceptor dynamicTableNameInnerInterceptor() {
        Map<String, TableNameHandler> map = new HashMap<>(1);
        map.put("points_board", (sql, tableName) -> TableInfoContext.getInfo()); // 返回新的表名
        map.put("points_record", (sql, tableName) -> {
            String newTableName = TableInfoContext.getInfo();
            return newTableName != null ? newTableName : tableName;
        });
        return new DynamicTableNameInnerInterceptor(map);
    }
}
