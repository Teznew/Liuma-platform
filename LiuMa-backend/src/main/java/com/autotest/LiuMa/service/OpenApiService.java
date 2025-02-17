package com.autotest.LiuMa.service;

import com.alibaba.fastjson.JSONObject;
import com.autotest.LiuMa.common.constants.*;
import com.autotest.LiuMa.common.exception.EngineVerifyException;
import com.autotest.LiuMa.common.exception.LMException;
import com.autotest.LiuMa.common.utils.*;
import com.autotest.LiuMa.database.domain.*;
import com.autotest.LiuMa.database.mapper.*;
import com.autotest.LiuMa.dto.ReportDTO;
import com.autotest.LiuMa.dto.TaskDTO;
import com.autotest.LiuMa.request.CaseResultRequest;
import com.autotest.LiuMa.request.EngineRequest;
import com.autotest.LiuMa.request.RunRequest;
import com.autotest.LiuMa.response.*;
import org.mybatis.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@Service
@Transactional(rollbackFor = Exception.class)
public class OpenApiService {

    @Value("${task.file.path}")
    public String TASK_FILE_PATH;

    @Value("${app.package.path}")
    private String APP_PACKAGE_PATH;

    @Value("${mail.sender.on-off}")
    private String MAIL_ON_OFF;

    @Value("${spring.mail.username}")
    private String MAIL_SENDER;
    
    @Value("${cloud.storage.on-off}")
    private String cloudStorage;  // 云存储开关

    @Value("${report.screenshot.path}")
    private String imagePath;  // 本地存储路径

    @Value("${qiniu.cloud.ak}")
    private String ak;   // 七牛云ak

    @Value("${qiniu.cloud.sk}")
    private String sk;    // 七牛云sk

    @Value("${qiniu.cloud.bucket}")
    private String imageBucket;    // 七牛云空间名

    @Value("${qiniu.cloud.uploadUrl}")
    private String uploadUrl;   // 七牛云上传域名

    @Resource
    private UserMapper userMapper;

    @Resource
    private EngineMapper engineMapper;

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private ReportMapper reportMapper;

    @Resource
    private PlanMapper planMapper;

    @Resource
    private PlanNoticeMapper planNoticeMapper;

    @Resource
    private TestFileMapper testFileMapper;

    @Resource
    private DebugDataMapper debugDataMapper;

    @Resource
    private CaseJsonCreateService caseJsonCreateService;

    @Resource
    private ReportUpdateService reportUpdateService;

    @Resource
    private NotificationService notificationService;

    @Resource
    private RunService runService;

    @Resource
    private ReportService reportService;

    @Resource
    private SendMailService sendMailService;

    public String applyEngineToken(EngineRequest request) {
        Engine engine = engineMapper.getEngineById(request.getEngineCode());
        if (request.getEngineSecret().equals(engine.getSecret())){
            return JwtUtils.createEngineToken(engine);
        }
        throw new EngineVerifyException("id或secret填写不正确");
    }

    public String engineHeartbeat(EngineRequest request) {
        Engine engine = engineMapper.getEngineById(request.getEngineCode());
        if(engine == null){
            throw new EngineVerifyException("引擎code不存在");
        }
        if (engine.getStatus().equals(EngineStatus.OFFLINE.toString())){
            engineMapper.updateStatus(request.getEngineCode(), EngineStatus.ONLINE.toString());
        }
        engineMapper.updateHeartbeat(request.getEngineCode(), System.currentTimeMillis());
        return "心跳成功";
    }

    public TaskResponse fetchEngineTask(EngineRequest request) {
        // 获取引擎任务
        TaskResponse response = new TaskResponse();
        Engine engine = engineMapper.getEngineById(request.getEngineCode());
        if(engine == null){
            throw new EngineVerifyException("引擎code不存在");
        }
        TaskDTO task;
        if(engine.getEngineType().equals(EngineType.SYSTEM.toString())){
            // 内置引擎调度优先调试后计划或集合
            task = taskMapper.getToRunTask(EngineType.SYSTEM.toString());
        }else {
            task = taskMapper.getToRunTask(engine.getId());
        }
        if(task == null){
            return null;
        }
        if(engine.getEngineType().equals(EngineType.SYSTEM.toString())) {
            // 更新 task执行引擎为当前获取
            int count = taskMapper.updateTaskEngine(request.getEngineCode(), task.getId());
            if (count == 0){    // 该条任务被别的引擎拿到 不再返回 等待下一次请求
                return null;
            }
        }
        if(task.getSourceId() == null){
            return null;
        }
        // 获取本任务需要测试的用例列表
        List<TaskTestCollectionResponse> testCollectionList = caseJsonCreateService.getTaskTestCollectionList(task);
        if(testCollectionList.size()==0) return null;
        try {
            // 组装测试数据 调试数据放在debugData中 计划或者集合数据生成json.zip 下载地址放在download中
            if (task.getSourceType().equals(ReportSourceType.TEMP.toString()) || task.getSourceType().equals(ReportSourceType.CASE.toString())) {
                JSONObject testCase = caseJsonCreateService.getDebugData(task, testCollectionList);
                response.setDownloadUrl(null);
                response.setDebugData(testCase);
            } else {
                // 计划和集合执行有下载测试数据地址 没有调试数据 测试数据打包放到下载地址下
                String downloadUrl = caseJsonCreateService.getDownloadUrl(task, testCollectionList);
                response.setDebugData(null);
                response.setDownloadUrl(downloadUrl);
            }
        }catch (Exception e){   // 出现错误直接给null
            response.setDownloadUrl(null);
            response.setDebugData(null);
        }
        response.setReRun(false);
        response.setMaxThread(1);
        if(task.getSourceType().equals(ReportSourceType.PLAN.toString())){
            Plan plan = planMapper.getPlanDetail(task.getSourceId());
            if(plan.getRetry().equals("Y")){
                response.setReRun(true);
            }
            response.setMaxThread(plan.getMaxThread());
        }
        response.setTaskId(task.getId());
        response.setTaskType(task.getType());
        response.setTestCollectionList(testCollectionList);
        // 更新任务及报告状态
        engineMapper.updateStatus(engine.getId(), EngineStatus.RUNNING.toString());
        taskMapper.updateTask(ReportStatus.RUNNING.toString(), task.getId());
        reportMapper.updateReportStatus(ReportStatus.RUNNING.toString(), task.getReportId());
        reportMapper.updateReportStartTime(task.getReportId(), System.currentTimeMillis(), System.currentTimeMillis());
        return response;
    }

    public String getTaskStatus(EngineRequest request){
        TaskDTO task = taskMapper.getTaskDetail(request.getTaskId());
        if(task.getStatus().equals(ReportStatus.DISCONTINUE.toString())){
            // 任务终止时 更新引擎状态为在线 并释放设备
            engineMapper.updateStatus(task.getEngineId(), EngineStatus.ONLINE.toString());
            runService.stopDeviceWhenRunEnd(task.getId());
            return "STOP";
        }
        return null;
    }

    public void uploadCaseResult(EngineRequest request){
        TaskDTO task = taskMapper.getTaskDetail(request.getTaskId());
        List<CaseResultRequest> caseResultList = request.getCaseResultList();
        reportUpdateService.updateReport(task, caseResultList);
    }

    public void completeEngineTask(EngineRequest request){
        TaskDTO task = taskMapper.getTaskDetail(request.getTaskId());
        // 任务更新完成
        taskMapper.updateTask(ReportStatus.COMPLETED.toString(), task.getId());
        // 统计报告信息
        ReportStatistics reportStatistics = reportMapper.getReportStatistics(task.getReportId());
        String reportStatus;
        if(reportStatistics.getErrorCount() > 0){
            reportStatus = ReportStatus.ERROR.toString();
        }else if(reportStatistics.getFailCount() > 0){
            reportStatus = ReportStatus.FAIL.toString();
        }else if(reportStatistics.getPassCount() > 0){
            reportStatus = ReportStatus.SUCCESS.toString();
        }else {
            reportStatus = ReportStatus.SKIP.toString();
        }
        engineMapper.updateStatus(request.getEngineCode(), EngineStatus.ONLINE.toString());
        reportMapper.updateReportStatus(reportStatus, task.getReportId());
        reportMapper.updateReportEndTime(task.getReportId(), System.currentTimeMillis(), System.currentTimeMillis());
        // 释放设备
        runService.stopDeviceWhenRunEnd(task.getId());
        // 删除任务文件 并通知执行人
        if(!task.getType().equals(TaskType.DEBUG.toString())){
            String taskZipPath = TASK_FILE_PATH+"/"+task.getProjectId()+"/"+task.getId()+".zip";
            FileUtils.deleteFile(taskZipPath);

            if(task.getSourceType().equals(ReportSourceType.PLAN.toString())){
                try {
                    if("on".equals(MAIL_ON_OFF)) {
                        // 邮件推送
                        User user = userMapper.getUserInfo(task.getCreateUser());
                        String title = "测试任务执行完成通知";
                        String content = user.getUsername() + ", 您好!<br><br>您执行的任务: \""
                                + task.getName() + "\" 已执行完毕，请登录平台查看结果。<br><br>谢谢！";
                        sendMailService.sendReportMail(MAIL_SENDER, user.getEmail(), title, content);
                    }
                }catch (Exception ignored){
                }
                // 计划执行需要走群消息通知
                PlanNotice planNotice = planNoticeMapper.getPlanNotice(task.getSourceId());
                if(planNotice == null){
                    return; //没有配置不通知
                }
                if(planNotice.getCondition().equals("F") && reportStatus.equals(ReportStatus.SUCCESS.toString())){
                    return; // 仅失败通知且结果成功不通知
                }
                Notification notification = notificationService.getNotificationById(planNotice.getNotificationId());
                if(notification.getStatus().equals(NotificationStatus.DISABLE.toString())){
                    return; // 通知禁用不通知
                }
                try {
                    notificationService.sendNotification(notification, task);   // 发送通知
                }catch (Exception ignored){
                }
            }
        }else {
            Report report = reportMapper.getReportDetail(task.getReportId());
            if (report.getSourceType().equals(ReportSourceType.TEMP.toString())){
                // 删除临时调试数据
                debugDataMapper.deleteDebugData(report.getSourceId());
            }
        }
    }

    public void uploadScreenshot(EngineRequest request) {
        try{
            if(cloudStorage.equals("on")){
                UploadUtils.uploadImageB64(request.getFileName(), request.getBase64String(), uploadUrl, imageBucket, ak, sk);
            }else {
                String fileName = request.getFileName();
                String path = imagePath + "/" + fileName.split("_")[0] + "/" + fileName.split("_")[1];
                ImageUtils.convertBase64ToImage(request.getBase64String(), path);
            }
        } catch (Exception exception) {
            throw new LMException("截图文件上传失败");
        }
    }

    public void downloadTestFile(String fileId, HttpServletResponse response) {
        TestFile testFile = testFileMapper.getTestFile(fileId);
        FileUtils.downloadFile(testFile.getFilePath(), response);
    }

    public void downloadAppPackage(String date, String fileId, String packageName, HttpServletResponse response) {
        String path = APP_PACKAGE_PATH + "/" + date + "/" + fileId + "/" + packageName;
        FileUtils.downloadFile(path, response);
    }

    public ResponseEntity<byte[]> previewImage(String date, String fileId) {
        String path = imagePath + "/" + date + "/" + fileId;
        return FileUtils.previewImage(path);
    }

    public void downTaskFile(String taskId, HttpServletResponse response) {
        TaskDTO task = taskMapper.getTaskDetail(taskId);
        String taskZipPath = TASK_FILE_PATH+"/"+task.getProjectId()+"/"+task.getId()+".zip";
        FileUtils.downloadFile(taskZipPath, response);
    }

    public String execTestPlan(RunRequest request){
        Plan plan = planMapper.getPlanDetail(request.getPlanId());
        if(plan==null){
            throw new LMException("测试计划不存在");
        }
        User user = userMapper.getUser(request.getUser());
        if(user==null){
            throw new LMException("用户账号不存在");
        }

        request.setSourceId(request.getPlanId());
        request.setSourceType(ReportSourceType.PLAN.toString());
        request.setSourceName("【外部执行】"+ plan.getName());
        request.setTaskType(TaskType.API.toString());
        request.setRunUser(user.getId());
        if(request.getEnvironmentId()==null){
            request.setEnvironmentId(plan.getEnvironmentId());
        }
        if(request.getEngineId()==null){
            request.setEngineId(plan.getEngineId());
        }
        request.setProjectId(plan.getProjectId());
        Task task = runService.run(request);
        return task.getId();
    }

    public ReportDTO getPlanReport(String taskId){
        TaskDTO taskDTO = taskMapper.getTaskDetail(taskId);
        if(taskDTO==null){
            throw new LMException("测试任务不存在");
        }
        return reportService.getPlanResult(taskDTO.getReportId());
    }

}
