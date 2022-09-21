package com.tencent.bk.devops.atom.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.bk.devops.atom.AtomContext;
import com.tencent.bk.devops.atom.common.Status;
import com.tencent.bk.devops.atom.pojo.AtomResult;
import com.tencent.bk.devops.atom.pojo.DataField;
import com.tencent.bk.devops.atom.pojo.StringData;
import com.tencent.bk.devops.atom.spi.AtomService;
import com.tencent.bk.devops.atom.spi.TaskAtom;
import com.tencent.bk.devops.atom.task.pojo.AtomParam;
import com.tencent.bk.devops.atom.task.pojo.DockerRunPortBinding;
import com.tencent.bk.devops.atom.utils.json.JsonUtil;
import com.tencent.bk.devops.plugin.docker.DockerApi;
import com.tencent.bk.devops.plugin.docker.pojo.DockerRunLogRequest;
import com.tencent.bk.devops.plugin.docker.pojo.DockerRunLogResponse;
import com.tencent.bk.devops.plugin.docker.pojo.DockerRunRequest;
import com.tencent.bk.devops.plugin.docker.pojo.DockerRunResponse;
import com.tencent.bk.devops.plugin.pojo.ErrorType;
import com.tencent.bk.devops.plugin.pojo.Result;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.tools.ant.filters.StringInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AtomService(paramClass = AtomParam.class)
public class DemoAtom implements TaskAtom<AtomParam> {

    private static final Logger logger = LoggerFactory.getLogger(DemoAtom.class);

    static final String JDBC_DRIVER = "com.mysql.jdbc.Driver";

    /**
     * 执行主入口
     *
     * @param atomContext 插件上下文
     */
    @Override
    public void execute(AtomContext<AtomParam> atomContext) {
        // 1.1 拿到请求参数
        AtomParam param = atomContext.getParam();
        logger.info("the param is :{}", JsonUtil.toJson(param));
        // 1.2 拿到初始化好的返回结果对象
        AtomResult result = atomContext.getResult();
        // 2. 校验参数失败直接返回
        checkParam(param, result);
        if (result.getStatus() != Status.success) {
            return;
        }
        // 3. 模拟处理插件业务逻辑
        String mysqlIp = "";
        String mysqlPort = "";
        try {
            String property = System.getenv("devops_slave_model");
            String devCloudProperty = System.getenv("devops.slave.environment");

            logger.info("property: " + property + ", devCloudProperty: " + devCloudProperty);

            String devCloudAppId = atomContext.getSensitiveConfParam("devCloudAppId");
            String devCloudUrl = atomContext.getSensitiveConfParam("devCloudUrl");
            String devCloudToken = atomContext.getSensitiveConfParam("devCloudToken");

            Map<String, String> extraOptions = new HashMap<>();
            extraOptions.put("portList", param.getPort());
            extraOptions.put("devCloudAppId", devCloudAppId);
            extraOptions.put("devCloudUrl", devCloudUrl);
            extraOptions.put("devCloudToken", devCloudToken);
            DockerRunRequest dockerRunRequest = new DockerRunRequest(
                    param.getPipelineStartUserName(),
                    "docker.io/" + param.getImageName(),
                    new ArrayList<>(),
                    "",
                    "",
                    Collections.singletonMap("MYSQL_ROOT_PASSWORD", param.getMysqlPw()),
                    new File(param.getBkWorkspace()),
                    extraOptions,
                    new HashMap<>(),
                    true,
                    Collections.singletonList(Integer.valueOf(param.getPort()))
            );
            // logger.info("dockerRunRequest:" + JsonUtil.toJson(dockerRunRequest));
            Result<DockerRunResponse> dockerRunResult = new DockerApi().dockerRunCommand(
                    param.getProjectName(),
                    param.getPipelineId(),
                    param.getPipelineBuildId(),
                    dockerRunRequest);
            if (dockerRunResult.isOk() && dockerRunResult.getData() != null) {
                final Map<String, DataField> data = result.getData();
                Map<String, Object> dockerRunResultMap = JsonUtil.fromJson(JsonUtil.toJson(dockerRunResult.getData()),
                        new TypeReference<Map<String, Object>>() {});
                Object dockerRunResultData = dockerRunResultMap.get("extraOptions");
                Map<String, Object> dockerRunResultDataObject = JsonUtil.fromJson(JsonUtil.toJson(dockerRunResultData),
                        new TypeReference<Map<String, Object>>() {});

                if (property != null && property.equals("docker")) {
                    String dockerRunPortBindingsStr =
                            JsonUtil.toJson(dockerRunResultDataObject.get("dockerRunPortBindings"));

                    ObjectMapper objectMapper = new ObjectMapper();
                    objectMapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
                    objectMapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                    logger.info("dockerRunPortBindingsStr: \n" + dockerRunPortBindingsStr);
                    List<DockerRunPortBinding> portBindings = objectMapper.readValue(dockerRunPortBindingsStr,
                            new TypeReference<List<DockerRunPortBinding>>() {});
                    mysqlIp = portBindings.get(0).getHostIp();
                    mysqlPort = portBindings.get(0).getHostPort().toString();
                }
                // devcloud轮训等待容器启动
                if (devCloudProperty != null && devCloudProperty.equals("DevCloud")) {
                    Map<String, String> dockerRunResultData1 = new HashMap<>();
                    for (String key : dockerRunResultDataObject.keySet()) {
                        dockerRunResultData1.put(key, dockerRunResultDataObject.get(key).toString());
                    }

                    HandleDevCloudStart handleDevCloudStart =
                            new HandleDevCloudStart(param, mysqlIp, mysqlPort, dockerRunResultData1).invoke();
                    mysqlIp = handleDevCloudStart.getMysqlIp();
                    mysqlPort = handleDevCloudStart.getMysqlPort();
                }

                if (mysqlIp.isEmpty() || mysqlPort.isEmpty()) {
                    throw new RuntimeException("Mysql Service init error, mysqlIp or mysqlPort is null.");
                }

                logger.info("the mysqlIp is :{}", mysqlIp);
                logger.info("the mysqlPort is :{}", mysqlPort);

                data.put("MYSQL_IP", new StringData(mysqlIp));
                data.put("MYSQL_PORT", new StringData(mysqlPort));

                // 轮训等待mysql服务启动
                for (int i = 0; i < 36; i++) {
                    Thread.sleep(5000);
                    if (checkMysqlConnection(mysqlIp, mysqlPort, "root", param.getMysqlPw())) {
                        break;
                    }
                }

                // 如果存在初始化脚本，执行脚本
                if (!param.getInitCmd().isEmpty()) {
                    initMysql(
                            mysqlIp,
                            mysqlPort,
                            "root",
                            param.getMysqlPw(),
                            param.getInitCmd()
                    );
                }
            }
        } catch (Exception e) {
            logger.error("execute error.", e);
            result.setStatus(Status.error);   // 状态设置为错误
            result.setErrorCode(500);           // 设置错误码用于错误定位和数据度量（500为样例实际，可取任意值）
            result.setErrorType(ErrorType.PLUGIN.getNum()); // 设置错误类型用于区分插件执行出错原因（也可直接传入1-3）
            result.setMessage("插件执行出错!");
        }
    }

    /**
     * 检查参数
     *
     * @param param  请求参数
     * @param result 结果
     */
    private void checkParam(AtomParam param, AtomResult result) {
        // 参数检查
        if (StringUtils.isBlank(param.getImageName())) {
            result.setStatus(Status.failure);   // 状态设置为失败
            result.setErrorCode(100);           // 设置错误码用于错误定位和数据度量（100为样例实际，可取任意值）
            result.setErrorType(ErrorType.USER.getNum()); // 设置错误类型用于区分插件执行出错原因（也可直接传入1-3）
            result.setMessage("镜像不能为空!");   // 失败信息回传给插件执行框架会打印出结果
        }

        if (StringUtils.isBlank(param.getPort())) {
            result.setStatus(Status.failure);   // 状态设置为失败
            result.setErrorCode(100);           // 设置错误码用于错误定位和数据度量（100为样例实际，可取任意值）
            result.setErrorType(ErrorType.USER.getNum()); // 设置错误类型用于区分插件执行出错原因（也可直接传入1-3）
            result.setMessage("端口不能为空!");   // 失败信息回传给插件执行框架会打印出结果
        }

        if (StringUtils.isBlank(param.getMysqlPw())) {
            result.setStatus(Status.failure);   // 状态设置为失败
            result.setErrorCode(100);           // 设置错误码用于错误定位和数据度量（100为样例实际，可取任意值）
            result.setErrorType(ErrorType.USER.getNum()); // 设置错误类型用于区分插件执行出错原因（也可直接传入1-3）
            result.setMessage("密码不能为空!");   // 失败信息回传给插件执行框架会打印出结果
        }

        /*
         其他比如判空等要自己业务检测处理，否则后面执行可能会抛出异常，状态将会是 Status.error
         这种属于插件处理不到位，算是bug行为，需要插件的开发去定位
          */
    }

    private DockerRunLogResponse getDockerStatus(AtomParam atomParam, Map<String, String> extraMap) {
        DockerRunLogRequest dockerRunLogRequest = new DockerRunLogRequest(
                atomParam.getPipelineStartUserName(),
                new File(atomParam.getBkWorkspace()),
                System.currentTimeMillis(),
                extraMap
        );
        Result<DockerRunLogResponse> dockerRunLogResponseResult = new DockerApi().dockerRunGetLog(
                atomParam.getProjectName(),
                atomParam.getPipelineId(),
                atomParam.getPipelineBuildId(),
                dockerRunLogRequest
        );

        if (dockerRunLogResponseResult.isOk() && dockerRunLogResponseResult.getData() != null) {
            return dockerRunLogResponseResult.getData();
        } else {
            return null;
        }
    }

    private Boolean checkMysqlConnection(String hostIp, String port, String user, String password) {
        Connection conn = null;
        Statement stmt = null;
        String queryParamStr =
        "?useSSL=false&autoReconnect=true&timezone=+800&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true";
        try {
            Class.forName(JDBC_DRIVER);
            conn = DriverManager.getConnection("jdbc:mysql://" + hostIp + ":" + port + queryParamStr,
                    user, password);
            stmt = conn.createStatement();
            String sql = "select 1";
            stmt.executeQuery(sql);

            return true;
        } catch (Exception se) {
            logger.warn("Mysql is waiting for connection ... ");
            return false;
        } finally {
            try {
                if (stmt != null) {
                    conn.close();
                }
            } catch (SQLException se) {
                se.printStackTrace();
            }

            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }

    private void initMysql(String hostIp, String port, String user, String password, String initCmd) {
        Connection conn = null;
        String queryParamStr =
         "?useSSL=false&autoReconnect=true&timezone=+800&useUnicode=true&characterEncoding=utf8&allowMultiQueries=true";
        try {
            Class.forName(JDBC_DRIVER);
            conn = DriverManager.getConnection("jdbc:mysql://" + hostIp + ":" + port + queryParamStr,
                    user, password);
            ScriptRunner runner = new ScriptRunner(conn);
            runner.setAutoCommit(true);//自动提交
            runner.setFullLineDelimiter(false);
            runner.setDelimiter(";"); //每条命令间的分隔符
            runner.setSendFullScript(false);
            runner.setStopOnError(true);

            InputStream inputStream = new StringInputStream(initCmd);
            runner.runScript(new InputStreamReader(inputStream));
            conn.close();
        } catch (Exception se) {
            se.printStackTrace();
            throw new RuntimeException("init sql error.", se);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }

    private class HandleDevCloudStart {
        private AtomParam param;
        private String mysqlIp;
        private String mysqlPort;
        private Map<String, String> dockerRunResultData1;

        public HandleDevCloudStart(
                AtomParam param,
                String mysqlIp,
                String mysqlPort, 
                Map<String, String> dockerRunResultData1
        ) {
            this.param = param;
            this.mysqlIp = mysqlIp;
            this.mysqlPort = mysqlPort;
            this.dockerRunResultData1 = dockerRunResultData1;
        }

        public String getMysqlIp() {
            return mysqlIp;
        }

        public String getMysqlPort() {
            return mysqlPort;
        }

        public HandleDevCloudStart invoke() throws InterruptedException {
            for (int i = 0; i < 60; i++) {
                Thread.sleep(5000);
                DockerRunLogResponse dockerRunLogResponse = getDockerStatus(param, dockerRunResultData1);
                // logger.info("dockerRunLogResponse: " + JsonUtil.toJson(dockerRunLogResponse));

                if (dockerRunLogResponse != null) {
                    String taskStatus = dockerRunLogResponse.getStatus();
                    if (taskStatus.equals("failure")) {
                        throw new RuntimeException("Mysql Service get task status error, dockerRunLogResponse: "
                                + JsonUtil.toJson(dockerRunLogResponse));
                    }

                    Map<String, String> dockerRunExtraOptions = dockerRunLogResponse.getExtraOptions();
                    if (dockerRunExtraOptions.containsKey("jobStatusFlag")
                            && (dockerRunExtraOptions.get("jobStatusFlag").equals("failure")
                            || dockerRunExtraOptions.get("jobStatusFlag").equals("error"))) {
                        throw new RuntimeException("Mysql Service get job status error, status: "
                                + dockerRunExtraOptions.get("jobStatusFlag").equals("failed"));
                    }

                    if (dockerRunExtraOptions.containsKey("jobStatusFlag")
                            && dockerRunExtraOptions.get("jobStatusFlag").equals("success")) {
                        mysqlIp = dockerRunExtraOptions.get("jobIp").toString();
                        mysqlPort = param.getPort();
                        break;
                    }
                    // 变更map，继续轮训
                    dockerRunResultData1 = dockerRunLogResponse.getExtraOptions();
                } else {
                    throw new RuntimeException("getDockerStatus error, dockerRunLogResponse is null.");
                }
            }
            return this;
        }
    }
}
