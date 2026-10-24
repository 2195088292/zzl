package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.zzl.common.core.domain.entity.SysRole;
import com.zzl.common.core.domain.entity.SysUser;
import com.zzl.common.core.domain.model.LoginUser;
import com.zzl.common.core.redis.RedisCache;
import com.zzl.framework.config.RedisConfig;
import com.zzl.framework.web.service.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的本地 HTTP/浏览器联调：只创建临时账号，不使用真实用户密码。 */
@EnabledIfEnvironmentVariable(named = "ZZL_AI_BROWSER_SMOKE", matches = "true")
class AiMenuBrowserSmokeTest
{
    @Test void verifyHttpAndProvideTemporaryBrowserSessions() throws Exception
    {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"), new ClassPathResource("application-druid.yml"));
        Properties props = yaml.getObject();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                props.getProperty("spring.datasource.druid.master.url"), props.getProperty("spring.datasource.druid.master.username"),
                props.getProperty("spring.datasource.druid.master.password")));
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(props.getProperty("spring.data.redis.host"),
                Integer.parseInt(props.getProperty("spring.data.redis.port")));
        config.setDatabase(Integer.parseInt(props.getProperty("spring.data.redis.database", "0")));
        String password = props.getProperty("spring.data.redis.password", "");
        if (!password.isBlank()) config.setPassword(RedisPassword.of(password));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet(); factory.start();
        RedisCache cache = new RedisCache();
        ReflectionTestUtils.setField(cache, "redisTemplate", new RedisConfig().redisTemplate(factory));
        TokenService tokens = new TokenService();
        ReflectionTestUtils.setField(tokens, "redisCache", cache);
        ReflectionTestUtils.setField(tokens, "header", props.getProperty("token.header"));
        ReflectionTestUtils.setField(tokens, "secret", props.getProperty("token.secret"));
        ReflectionTestUtils.setField(tokens, "expireTime", 10);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Path output = Path.of("..", "output", "diagnostics").toAbsolutePath().normalize();
        Path ready = output.resolve("ai-browser-sessions.json");
        Path done = output.resolve("ai-browser-done.flag");
        List<Map<String, Object>> fixtures = new ArrayList<>();
        List<Long> users = new ArrayList<>(), roles = new ArrayList<>();
        List<String> sessionKeys = new ArrayList<>();
        try
        {
            Files.createDirectories(output); Files.deleteIfExists(done);
            Long root = jdbc.queryForObject("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='ai'", Long.class);
            for (String kind : List.of("none", "chat", "reader", "full"))
            {
                String suffix = UUID.randomUUID().toString().substring(0, 10);
                String name = "ai-ui-" + suffix;
                jdbc.update("INSERT INTO sys_user(user_name,nick_name,pwd_update_date) VALUES (?, ?,NOW())", name, "权限验证");
                Long userId = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, name);
                users.add(userId);
                jdbc.update("INSERT INTO sys_role(role_name,role_key,role_sort,status) VALUES (?,?,1,'0')", name, name);
                Long roleId = jdbc.queryForObject("SELECT role_id FROM sys_role WHERE role_key=?", Long.class, name);
                roles.add(roleId);
                jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)", userId, roleId);
                List<Long> menuIds = switch (kind)
                {
                    case "chat" -> jdbc.queryForList("SELECT menu_id FROM sys_menu WHERE perms IN ('ai:workbench:query','ai:chat:send')", Long.class);
                    case "reader" -> jdbc.queryForList("SELECT menu_id FROM sys_menu WHERE perms IN ('ai:model:list','ai:knowledge:list')", Long.class);
                    case "full" -> jdbc.queryForList("SELECT menu_id FROM sys_menu WHERE perms LIKE 'ai:%'", Long.class);
                    default -> new ArrayList<>();
                };
                if (!menuIds.isEmpty()) menuIds.add(root);
                for (Long menuId : menuIds) jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (?,?)", roleId, menuId);
                Set<String> permissions = new HashSet<>(jdbc.queryForList("SELECT m.perms FROM sys_menu m JOIN sys_role_menu rm ON m.menu_id=rm.menu_id WHERE rm.role_id=? AND m.perms<>''", String.class, roleId));
                SysRole role = new SysRole(); role.setRoleId(roleId); role.setRoleKey(name); role.setStatus("0");
                SysUser user = new SysUser(userId); user.setUserName(name); user.setNickName("权限验证");
                user.setRoles(List.of(role)); user.setPwdUpdateDate(new Date());
                LoginUser login = new LoginUser(userId, 100L, user, permissions);
                String token = tokens.createToken(login);
                sessionKeys.add(login.getToken());
                var routers = request(token, "/getRouters");
                assertEquals(200, routers.getIntValue("code"));
                int expectedPages = switch (kind) { case "chat" -> 1; case "reader" -> 2; case "full" -> 3; default -> 0; };
                var routes = routers.getJSONArray("data");
                assertEquals(expectedPages == 0 ? 0 : 1, routes.size());
                if (expectedPages > 0) assertEquals(expectedPages, routes.getJSONObject(0).getJSONArray("children").size());
                assertEquals(kind.equals("none") ? 403 : 200, request(token, "/system/ai/models").getIntValue("code"));
                if (!kind.equals("full")) assertEquals(403, request(token, "/system/ai/models/sample/test", "POST").getIntValue("code"));
                fixtures.add(Map.of("kind", kind, "token", token));
            }
            Files.writeString(ready, JSON.toJSONString(fixtures));
            System.out.println("AI browser fixtures ready; HTTP role checks passed. Tokens are not logged.");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
            while (!Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(250);
            assertTrue(Files.exists(done), "浏览器验证未发出完成信号，临时账号已清理");
        }
        finally
        {
            for (String key : sessionKeys) tokens.delLoginUser(key);
            for (Long role : roles) { jdbc.update("DELETE FROM sys_role_menu WHERE role_id=?", role); jdbc.update("DELETE FROM sys_role WHERE role_id=?", role); }
            for (Long user : users) { jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", user); jdbc.update("DELETE FROM sys_user WHERE user_id=?", user); }
            Files.deleteIfExists(ready); Files.deleteIfExists(done);
            RequestContextHolder.resetRequestAttributes(); factory.destroy();
        }
    }

    private com.alibaba.fastjson2.JSONObject request(String token, String path) throws Exception
    {
        return request(token, path, "GET");
    }

    private com.alibaba.fastjson2.JSONObject request(String token, String path, String method) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path))
                .header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return JSON.parseObject(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
}
