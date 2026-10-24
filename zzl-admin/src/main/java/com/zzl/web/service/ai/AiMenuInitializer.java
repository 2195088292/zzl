package com.zzl.web.service.ai;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 一次性导入 AI 动态菜单；后续管理操作以 sys_menu 为准。 */
@Component
public class AiMenuInitializer
{
    private final DataSource dataSource;

    public AiMenuInitializer(JdbcTemplate jdbc)
    {
        this.dataSource = jdbc.getDataSource();
    }

    @PostConstruct
    public void initialize()
    {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("db/ai-menu.sql"));
            script.setSqlScriptEncoding("UTF-8");
            script.execute(dataSource);
        });
    }
}
