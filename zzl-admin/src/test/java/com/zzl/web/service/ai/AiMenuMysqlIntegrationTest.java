package com.zzl.web.service.ai;

import com.zzl.system.mapper.SysMenuMapper;
import com.zzl.system.service.impl.SysMenuServiceImpl;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 真实菜单查询及路由构建，整个验证事务回滚，不保留测试用户或角色。 */
@EnabledIfEnvironmentVariable(named = "ZZL_AI_MYSQL_SMOKE", matches = "true")
class AiMenuMysqlIntegrationTest
{
    @Test void migrateOnceAndBuildRoutesFromRoleMenus() throws Exception
    {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-druid.yml"));
        Properties props = yaml.getObject();
        DriverManagerDataSource source = new DriverManagerDataSource(
                props.getProperty("spring.datasource.druid.master.url"),
                props.getProperty("spring.datasource.druid.master.username"),
                props.getProperty("spring.datasource.druid.master.password"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.zzl.common.core.domain.entity");
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysMenuMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        SysMenuMapper mapper = session.getMapper(SysMenuMapper.class);
        SysMenuServiceImpl menus = new SysMenuServiceImpl();
        ReflectionTestUtils.setField(menus, "menuMapper", mapper);
        AiMenuInitializer initializer = new AiMenuInitializer(jdbc);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(transaction -> {
            try
            {
                initializer.initialize();
                Long root = jdbc.queryForObject("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='ai'", Long.class);
                Long workbench = jdbc.queryForObject("SELECT menu_id FROM sys_menu WHERE perms='ai:workbench:query'", Long.class);
                assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE parent_id=? AND menu_type='C'", Integer.class, root));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='ai.menu.version'", Integer.class));
                assertEquals(3, menus.buildMenus(menus.selectMenuTreeByUserId(1L)).stream()
                        .filter(route -> "/ai".equals(route.getPath())).findFirst().orElseThrow().getChildren().size());
                String suffix = UUID.randomUUID().toString().substring(0, 12);
                jdbc.update("INSERT INTO sys_user(user_name,nick_name) VALUES (?,?)", "ai-test-" + suffix, "菜单测试");
                Long user = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO sys_role(role_name,role_key,role_sort,status) VALUES (?,?,1,'0')", "AI 菜单测试", "ai-test-" + suffix);
                Long role = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)", user, role);
                assertTrue(menus.selectMenuTreeByUserId(user).isEmpty());
                jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (?,?),(?,?)", role, root, role, workbench);
                session.clearCache();
                var routes = menus.buildMenus(menus.selectMenuTreeByUserId(user));
                assertEquals(1, routes.size());
                assertEquals("/ai", routes.get(0).getPath());
                assertEquals(1, routes.get(0).getChildren().size());
                assertEquals("AiWorkbench", routes.get(0).getChildren().get(0).getName());
                assertEquals("ai/workbench/index", routes.get(0).getChildren().get(0).getComponent());
                assertTrue(menus.selectMenuPermsByUserId(user).contains("ai:workbench:query"));
                assertFalse(menus.selectMenuPermsByUserId(user).contains("ai:model:add"));
                jdbc.update("UPDATE sys_menu SET menu_name='我的智能工作区',order_num=23 WHERE menu_id=?", root);
                jdbc.update("UPDATE sys_menu SET status='1' WHERE menu_id=?", workbench);
                jdbc.update("DELETE FROM sys_menu WHERE perms='ai:model:remove'");
                initializer.initialize();
                session.clearCache();
                assertEquals("我的智能工作区", jdbc.queryForObject("SELECT menu_name FROM sys_menu WHERE menu_id=?", String.class, root));
                assertEquals(23, jdbc.queryForObject("SELECT order_num FROM sys_menu WHERE menu_id=?", Integer.class, root));
                assertEquals("1", jdbc.queryForObject("SELECT status FROM sys_menu WHERE menu_id=?", String.class, workbench));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms='ai:model:remove'", Integer.class));
                assertTrue(menus.selectMenuTreeByUserId(user).get(0).getChildren().isEmpty());
            }
            finally
            {
                transaction.setRollbackOnly();
            }
        });
    }
}
