package com.isscloud.llm.controller.llm;

import ai.dify.javaclient.ChatClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.iss.rdp.common.base.BaseDAO;
import com.iss.rdp.common.utils.StringUtils;
import com.isscloud.llm.service.DifyConfigService;
import okhttp3.OkHttp;
import org.isscloud.common.base.BaseController;
import org.isscloud.common.base.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import okhttp3.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.io.InputStream;
import java.io.InputStreamReader;
/**
 * Dify API控制器
 */
@RestController
@RequestMapping("/dify")
public class DifyController extends BaseController {

    @Autowired
    private BaseDAO baseDAO;

    @Autowired
    private DifyConfigService difyConfigService;

    /**
     * 发送消息到Dify
     */
    @PostMapping(value = "/{appId}/chat")
    public ResponseBodyEmitter chat(@PathVariable String appId, @RequestBody Map<String, Object> params) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(300000L);
        try {
            String query = (String) params.get("query");
            if (appId == null || appId.trim().isEmpty()) {
               // return Result.error("应用ID不能为空");
            }
            if (query == null) {
               // return Result.error("查询内容不能为空");
            }

            // 获取可选参数
            @SuppressWarnings("unchecked")
            Map<String, String> inputs = (Map<String, String>) params.getOrDefault("inputs", new HashMap<>());
            //是否流式输出
            boolean stream = Boolean.parseBoolean(params.getOrDefault("stream", "true").toString());
            String user = getUser().getUserid();

            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            //会话编号
            String conversationId = (String) params.get("conversation_id");
            ChatClient chatClient = new ChatClient(app_key, apiUrl);
            okhttp3.Response responseDify = chatClient.createChatMessage(inputs, query, user, stream, conversationId);

            // 设置响应头
//            response.setContentType("text/event-stream");
//            response.setCharacterEncoding("UTF-8");
//            response.setHeader("Cache-Control", "no-cache");
//            response.setHeader("Connection", "keep-alive");
//            response.setHeader("Access-Control-Allow-Origin", "*");
//            response.setHeader("Access-Control-Allow-Headers", "*");
//            response.setHeader("Access-Control-Allow-Methods", "POST");

            new Thread(() -> {
                try {
                    streamResponse(responseDify, emitter);
                    emitter.complete();
                } catch (Exception e) {
                    e.printStackTrace();
                    emitter.completeWithError(e);
                }
            }).start();

            return emitter;
        } catch (Exception e) {
            e.printStackTrace();
            try {
                emitter.send(Result.error("发送消息失败: " + e.getMessage()).toString());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            emitter.complete();
            return emitter;
        }
    }

    private void streamResponse(okhttp3.Response responseChatMessage, ResponseBodyEmitter emitter) {
        try (okhttp3.ResponseBody responseBody = responseChatMessage.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream()))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                // 确保每个消息都是SSE格式
                if (!line.startsWith("data: ")) {
                    line = "data: " + line;
                }
                emitter.send(line + "\n\n", MediaType.TEXT_EVENT_STREAM);
            }
        } catch (IOException e) {
            try {
                emitter.send("data: " + Result.error("读取响应流失败: " + e.getMessage()).toString() + "\n\n", 
                           MediaType.TEXT_EVENT_STREAM);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    /**
     * 停止消息响应
     */
    @PostMapping("/{appId}/chat/stop")
    public Result stopChatMessage(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            String taskId = params.get("taskId");
            if (appId == null || appId.trim().isEmpty()) {
                return Result.error("应用ID不能为空");
            }
            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key,apiUrl);
            Response response = chatClient.stopMessage(taskId, appId);
            return Result.ok(response.body().string());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("停止消息响应失败: " + e.getMessage());
        }
    }

    /**
     * 获取应用Meta信息
     */
    @PostMapping("/{appId}/meta")
    public Result getMeta(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            if (appId == null || appId.trim().isEmpty()) {
                return Result.error("应用ID不能为空");
            }

            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");

            if (appId == null || apiUrl == null) {
                return Result.error("未找到API配置信息");
            }

            ChatClient chatClient = new ChatClient(app_key, apiUrl);
            
            // 获取Meta信息
            Response metaResponse = chatClient.getMeta(appId);
            String metaInfo = metaResponse.body().string();
            
            return Result.ok(metaInfo);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("获取Meta信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取应用参数信息
     */
    @PostMapping("/{appId}/parameters")
    public Result getParameters(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            if (appId == null || appId.trim().isEmpty()) {
                return Result.error("应用ID不能为空");
            }
            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key, apiUrl);
            // 获取应用参数信息
            Response appResponse = chatClient.getApplicationParameters(appId);
            String appParams = appResponse.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(appParams, new TypeToken<Map<String, Object>>(){}.getType());

            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("获取应用参数失败: " + e.getMessage());
        }
    }

    /**
     * 获取会话列表
     */
    @PostMapping("/{appId}/conversations")
    public Result getConversations(@PathVariable String appId, @RequestBody Map<String, Object> params) {
        try {
            if (appId == null || appId.trim().isEmpty()) {
                return Result.error("应用ID不能为空");
            }

            // 获取可选参数
            String user = getUser().getUserid();
            //限制返回的最大对话数。通过这个参数，可以控制每次请求最多返回多少条对话记录。
            int limit = Integer.parseInt(params.getOrDefault("limit", 20).toString());
            //对话是否被"置顶"或"标记"。这个参数用于过滤是否只获取被标记为"置顶"或特定状态的对话。
            boolean first = Boolean.parseBoolean(params.getOrDefault("first", false).toString());
            //第一个对话的 ID，用来定义从哪个对话开始获取数据。这通常是为了分页获取对话（例如，如果要从特定位置开始获取）
            String first_id = String.valueOf(params.getOrDefault("first_id", "").toString());

            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key, apiUrl);
            // 获取会话列表
            Response response = chatClient.getConversations(user, first_id, limit, first);
            String conversationList = response.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(conversationList, new TypeToken<Map<String, Object>>(){}.getType());

            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("获取会话列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取会话历史消息
     */
    @PostMapping("/{appId}/conversation/messages")
    public Result getConversationMessages(@PathVariable String appId, @RequestBody Map<String, Object> params) {
        try {
            if (appId == null || appId.trim().isEmpty()) {
                return Result.error("应用ID不能为空");
            }
            String conversationId = (String) params.get("conversationId");
            if (appId == null || conversationId == null) {
                return Result.error("会话ID不能为空");
            }

            String user = getUser().getUserid();
            String first_id = params.get("firstId") != null ? params.get("firstId").toString() : null;
            int limit = Integer.parseInt(params.getOrDefault("limit", 20).toString());

            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key,apiUrl);

            Response response = chatClient.getConversationMessages(user,conversationId,first_id,limit);
            String conversationList = response.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(conversationList, new TypeToken<Map<String, Object>>(){}.getType());

            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("获取会话历史消息失败: " + e.getMessage());
        }
    }

    /**
     * 获取下一轮建议问题列表
     */
    @PostMapping("/{appId}/conversation/suggested")
    public Result getSuggestedMessages(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            String message_id = params.get("message_id");
            if (appId == null || message_id == null) {
                return Result.error("应用ID和消息ID不能为空");
            }
            String user = getUser().getUserid();
            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key,apiUrl);
            Response response = chatClient.getSuggestedMessages(user,message_id);
            String suggestedList = response.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(suggestedList, new TypeToken<Map<String, Object>>(){}.getType());

            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("获取建议问题失败: " + e.getMessage());
        }
    }

    /**
     * 消息反馈（点赞/踩）
     */
    @PostMapping("/{appId}/message/feedback")
    public Result messageFeedback(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            String messageId = params.get("messageId");
            String rating = params.get("rating");  // like 或 dislike
            if (messageId == null || rating == null || appId == null) {
                return Result.error("���息ID、评分类型和应用ID不能为空");
            }
            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key, apiUrl);
            Response response = chatClient.messageFeedback(messageId, rating, appId);
            String feedbackList = response.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(feedbackList, new TypeToken<Map<String, Object>>(){}.getType());
            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("消息反馈失败: " + e.getMessage());
        }
    }

    /**
     * 重命名会话
     */
    @PostMapping("/{appId}/conversation/rename")
    public Result renameConversation(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            String conversationId = params.get("conversationId");
            String name = params.get("name");
            if (StringUtils.isEmpty(conversationId) || StringUtils.isEmpty(name) || appId == null) {
                return Result.error("会话ID、新名称和应用ID不能为空");
            }
            String user = getUser().getUserid();
            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key,apiUrl);
            Response response = chatClient.renameConversation(conversationId, name, user);
            String renameList = response.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(renameList, new TypeToken<Map<String, Object>>(){}.getType());

            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("重命名会话失败: " + e.getMessage());
        }
    }

    /**
     * 删除会话
     */
    @PostMapping("/{appId}/conversation/delete")
    public Result deleteConversation(@PathVariable String appId,@RequestBody Map<String, String> params) {
        try {
            String conversationId = params.get("conversationId");
            if (conversationId == null || appId == null) {
                return Result.error("会话ID和应用ID不能为空");
            }
            // 从数据库获取配置信息
            Map<String, String> config = difyConfigService.getDifyConfig(appId);
            String app_key = config.get("app_key");
            Map<String, String> sysParameter = difyConfigService.getSysParameter();
            String apiUrl = sysParameter.get("chparavalue");
            ChatClient chatClient = new ChatClient(app_key,apiUrl);
            String user = getUser().getUserid();
            Response response = chatClient.deleteConversation(conversationId, user);
            String delList = response.body().string();
            // 创建 Gson 实例
            Gson gson = new Gson();
            // 使用 TypeToken 来定义 Map 的类型
            Map<String, Object> map = gson.fromJson(delList, new TypeToken<Map<String, Object>>(){}.getType());

            return Result.ok(map);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("删除会话失败: " + e.getMessage());
        }
    }
} 