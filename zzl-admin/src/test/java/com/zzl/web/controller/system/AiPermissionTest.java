package com.zzl.web.controller.system;

import com.zzl.common.core.domain.entity.SysUser;
import com.zzl.common.core.domain.model.LoginUser;
import com.zzl.framework.web.service.PermissionService;
import com.zzl.web.service.ai.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在真实方法权限拦截器上验证，避免只检查注解字符串。 */
class AiPermissionTest
{
    private AnnotationConfigApplicationContext context;
    private AiChatService chatService;

    @Configuration
    @EnableMethodSecurity
    static class SecurityConfiguration {}

    @BeforeEach void start()
    {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext();
        context.register(SecurityConfiguration.class);
        context.registerBean("ss", PermissionService.class);
        chatService = mock(AiChatService.class);
        context.registerBean(AiChatController.class, () -> new AiChatController(mock(SysDashboardController.class),
                chatService, mock(AiConversationStore.class), mock(AiFileService.class), mock(AiKnowledgeBaseService.class)));
        context.registerBean(AiWorkspaceController.class, () -> new AiWorkspaceController(mock(AiFileService.class),
                chatService, mock(SysDashboardController.class), mock(AiRunService.class), mock(AiRagService.class),
                mock(AiRagIndexingService.class), mock(AiKnowledgeBaseService.class)));
        context.registerBean(AiRunController.class, () -> new AiRunController(mock(AiRunService.class), mock(AiAgentRunService.class)));
        context.registerBean(AiKnowledgeBaseController.class, () -> new AiKnowledgeBaseController(
                mock(AiKnowledgeBaseService.class), mock(AiKnowledgeBaseConfigService.class)));
        context.refresh();
    }

    @AfterEach void close()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    private void login(String... permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(999L);
        LoginUser login = new LoginUser(999L, 1L, user, Set.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
    }

    @Test void noAiPermissionBlocksEveryMappedEndpoint()
    {
        login("system:user:list");
        for (Class<?> type : Arrays.asList(AiChatController.class, AiWorkspaceController.class,
                AiRunController.class, AiKnowledgeBaseController.class))
        {
            Object controller = context.getBean(type);
            Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                    .forEach(method -> assertThrows(AccessDeniedException.class, () ->
                    {
                        Object[] args = Arrays.stream(method.getParameterTypes())
                                .map(parameter -> parameter == int.class ? 0 : null).toArray();
                        try { method.invoke(controller, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    }, type.getSimpleName() + "." + method.getName()));
        }
    }

    @Test void chatUserCanChooseModelsWithoutManagingConnections()
    {
        login("ai:workbench:query", "ai:chat:send");
        AiChatController controller = context.getBean(AiChatController.class);
        assertDoesNotThrow(controller::models);
        verify(chatService).modelViews("999");
        assertThrows(AccessDeniedException.class, () -> controller.addModel(new AiChatController.AiModelRequest()));
        assertThrows(AccessDeniedException.class, () -> controller.deleteModel("sample"));
        assertThrows(AccessDeniedException.class, () -> controller.modelCatalog(new AiChatController.AiModelRequest()));
        assertThrows(AccessDeniedException.class, () -> context.getBean(AiKnowledgeBaseController.class).create(null));
    }

    @Test void modelReaderCannotEditAndKnowledgeReaderCannotUpload()
    {
        login("ai:model:list", "ai:knowledge:list");
        assertDoesNotThrow(() -> context.getBean(AiChatController.class).models());
        assertDoesNotThrow(() -> context.getBean(AiKnowledgeBaseController.class).list());
        assertThrows(AccessDeniedException.class, () -> context.getBean(AiChatController.class)
                .updateModel("sample", new AiChatController.AiModelRequest()));
        assertThrows(AccessDeniedException.class, () -> context.getBean(AiKnowledgeBaseController.class).upload("sample", null));
        assertThrows(AccessDeniedException.class, () -> context.getBean(AiKnowledgeBaseController.class).updateSettings("sample", null));
    }
}
