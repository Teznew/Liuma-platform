/**
 * 用例中心  接口管理
 */
<template>
  <div>
    <!-- 搜索筛选 -->
    <el-form :inline="true" :model="searchForm">
        <el-form-item label="">
            <el-input size="small" v-model="searchForm.condition" prefix-icon="el-icon-search" placeholder="请输入接口NO、名称、地址"/>
        </el-form-item>
        <el-form-item>
            <el-button size="small" type="primary" @click="search">搜索</el-button>
            <el-button size="small" @click="reset">重置</el-button>
        </el-form-item>
        <el-form-item style="float: right">
            <el-button size="small" type="primary" icon="el-icon-plus" @click="addApi">新增接口</el-button>
        </el-form-item>
          <el-form-item style="float: right">
          <el-button size="small" type="success" icon="el-icon-plus" @click="importApi">导入接口</el-button>
        </el-form-item>
    </el-form>
    <!-- 接口模块 -->
    <el-col :span="4" class="left-tree">
        <module-tree title="接口模块" :treeData="treeData" :currentModule="searchForm.moduleId" @clickModule="clickModule($event)" @appendModule="appendModule($event)"
            @removeModule="removeModule(arguments)" @dragNode="dragNode(arguments)"/>
    </el-col>
    <!--接口列表-->
    <el-col :span="20" class="right-table">
        <el-table size="small" :data="apiListData" v-loading="loading" element-loading-text="拼命加载中">
            <el-table-column prop="num" label="NO" width="60px"/>
            <el-table-column prop="name" label="接口名称" min-width="180"/>
            <el-table-column prop="path" label="接口地址" min-width="150"/>
            <el-table-column prop="moduleName" label="所属模块"/>
            <el-table-column prop="username" label="创建人"/>
            <el-table-column prop="updateTime" label="更新时间" width="150"/>
            <el-table-column fixed="right" align="operation" label="操作" width="150">
                <template slot-scope="scope">
                    <el-button type="text" size="mini" @click="editApi(scope.row)">编辑</el-button>
                    <el-button type="text" size="mini" @click="deleteApi(scope.row)">删除</el-button>
                    <el-button type="text" size="mini" @click="generateCase(scope.row)">生成用例</el-button>
                </template>
            </el-table-column>
        </el-table>
        <!-- 分页组件 -->
        <Pagination v-bind:child-msg="pageParam" @callFather="callFather"></Pagination>
    </el-col>
    <!-- 添加模块弹窗 -->
    <module-append :title="title" :show.sync="moduleVisible" :moduleForm="moduleForm" @closeDialog="closeDialog" @submitModule="submitModule($event)"/>
    <!--上传文件的弹窗-->
    <el-dialog title="上传文件" :visible.sync="uploadFileVisible" width="600px" destroy-on-close>
      <el-form label-width="120px" style="padding-right: 30px;" :model="uploadFileForm" :rules="rules" ref="uploadFileForm">
        <el-form-item label="文件来源" prop="sourceType">
          <el-radio-group v-model="uploadFileForm.sourceType">
            <el-radio label="postman">postman</el-radio>
            <el-radio label="swagger">swagger3</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="选择模块" prop="moduleId">
          <select-tree style="width:90%" placeholder="请选择导入后的模块" :selectedValue="uploadFileForm.moduleId"
                       :selectedLabel="uploadFileForm.moduleName" :treeData="treeData" @selectModule="selectModule($event)"/>
        </el-form-item>
        <el-form-item label="选择文件" prop="fileList">
          <el-upload class="upload-demo" :file-list="uploadFileForm.fileList" :before-upload="beforeUpload" :http-request="uploadFile"
                     :on-remove="removeFile" :on-exceed="handleExceed" drag action :limit="1" ref="upload">
            <i class="el-icon-upload"></i>
            <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
            <div class="el-upload__tip" slot="tip">只能上传单个文件，且不超过50M</div>
          </el-upload>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button size="small" @click="uploadFileVisible=false">取消</el-button>
        <el-button size="small" type="primary" @click="submitFileForm('uploadFileForm', uploadFileForm)">上传</el-button>
      </div>
    </el-dialog>
    <!-- 自动生成用例配置 -->
    <el-drawer :visible.sync="editRuleVisible" direction="rtl" :with-header="false" destroy-on-close size="920px">
        <div class="api-drawer-header">
            <span style="float: left; font-size: 16px;">生成规则配置</span>
            <el-button size="small" type="primary" style="float: right;" @click="submitRuleForm(paramRuleForm)">确定</el-button>
        </div>
        <div class="api-drawer-body">
            <autocase :paramRuleForm="paramRuleForm"/>
        </div>
    </el-drawer>
  </div>
</template>

<script>
import Pagination from '../common/components/pagination'
import ModuleTree from './common/module/moduleTree'
import ModuleAppend from './common/module/moduleAppend'
import {timestampToTime} from '@/utils/util'
import SelectTree from "../common/business/selectTree";
import Autocase from "./common/case/autocase"

export default {
    // 注册组件
    components: {
        Pagination, ModuleTree, ModuleAppend, SelectTree, Autocase
    },
    data() {
        return{
            uploadFileVisible: false,
            uploadFileForm : { 
              sourceType: "postman",
              fileList: [],
              moduleId:"",  
              moduleName:""
            },
            rules:{
              sourceType:[{ required: true, message: '文件来源不能为空', trigger: 'blur' }],
              fileList: [{ required: true, message: '文件不能为空', trigger: 'blur' }],
              moduleId: [{ required: true, message: '导入模块不能为空', trigger: 'blur' }]
            },
            loading:false,
            moduleVisible: false,
            moduleForm: {
                name: "",
                parentId: "",
                parentName: "",
                data: "",
            },
            title: '添加接口模块',
            searchForm: {
                page: 1,
                limit: 10,
                condition: "",
                moduleId: "",
            },
            moduleList:[] , //存放当前项目中所有module的列表
            apiListData: [],
            pageParam: {
                currentPage: 1,
                pageSize: 10,
                total: 0
            },
            treeData: [], //  存放所有module的数据: /autotest/module/list/api/的响应结果
            editRuleVisible: false,
            paramRuleForm: {
                apiId: null,
                header: [],
                body: [],
                query: [],
                rest: [],
                positiveAssertion: [],
                oppositeAssertion: []
            }
        }
    },
    created() {
        // 加载面包屑
        this.$root.Bus.$emit('initBread', ["用例中心", "接口管理"])
        this.getTree()
        this.getdata(this.searchForm)
    },
    methods: {
      // 上传前判断格式和大小
      beforeUpload(file) {
        if (file.size > 50 * 1024 * 1024) {
          this.$message.warning('文件大小超过50M 无法上传');
          return false;
        }
        return true;
      },
      uploadFile(option) {
        this.uploadFileForm.fileList.push(option.file);
        this.uploadFileForm.name = option.file.name;
        this.$refs.uploadFileForm.validateField('fileList');
      },
      removeFile() {
        this.uploadFileForm.fileList = [];
      },
      handleExceed() {
        this.$message.warning('一次最多只能上传一个文件');
      },
      selectModule(data){
        this.uploadFileForm.moduleId = data.id;
        this.uploadFileForm.moduleName = data.label;

      },
      submitFileForm(confirm, form){ 
        this.$refs[confirm].validate(valid => {
          if (valid) {
              let url = '/autotest/import/api';
              let data = {
                projectId: this.$store.state.projectId,
                moduleId: form.moduleId,
                sourceType: form.sourceType
              };
              let file = form.fileList[0];
              this.$fileUpload(url, file, null, data, response =>{
                  this.$message.success("上传成功");
                  this.uploadFileVisible = false; 
                  this.getdata(this.searchForm);
              });
          }else{
              return false;
          }
        });
      },
        // 点击模块
        clickModule(data){
          this.searchForm.moduleId = data.id;
            this.getdata(this.searchForm);
        },
        // 添加模块
        appendModule(data) {
          if (data){
                this.moduleForm.parentId = data.id;
                this.moduleForm.parentName = data.label;
                this.moduleForm.data = data;
            }else{
                this.moduleForm.parentId = 0;
                this.moduleForm.parentName = "根节点";
                this.moduleForm.data = "";
            }
            this.moduleVisible = true;
        },
        // 删除模块
        removeModule(args) {
            let node = args[0];
            let data = args[1];
            if(data.children.length != 0){
                this.$message.warning("当前模块有子模块, 无法删除");
                return;
            }
            let url = '/autotest/module/delete';
            this.$post(url, data, response =>{
                const parent = node.parent;
                const children = parent.data.children || parent.data;
                const index = children.findIndex(d => d.id === data.id);
                children.splice(index, 1);
                this.$message.success("模块删除成功")
            });
        },
        // 拖拽模块
        dragNode(args){
            let dragNode = args[0];
            let newParent = args[1];
            let url = '/autotest/module/save';
            let moduleForm = dragNode.data;
            moduleForm.parentId = newParent;
            this.$post(url, moduleForm, response =>{
                this.$message.success("更改成功")
            });
        },
        // 关闭弹框
        closeDialog(){
            this.moduleVisible = false;
        },
        // 提交模块保存
        submitModule(moduleForm) {
            moduleForm.projectId = this.$store.state.projectId;
            moduleForm.moduleType = 'api_module';
            let url = '/autotest/module/save';
            this.$post(url, moduleForm, response =>{
                const newChild = response.data;
                if (moduleForm.parentId === 0){
                    this.treeData.push(newChild);
                }else{
                    if (!this.moduleForm.data.children){
                        this.$set(this.moduleForm.data, 'children', []);
                    }
                    this.moduleForm.data.children.push(newChild);
                }
                this.moduleVisible = false;
                this.moduleForm.name = "";
            });
        },
        // 获取树数据
        getTree(){
          let url = '/autotest/module/list/api/' + this.$store.state.projectId;
            this.$get(url, response =>{
                this.treeData = response.data;
            });
        },
        // 获取列表数据方法
        getdata(searchParam) {
            this.loading = true;
            let url = '/autotest/api/list/' + searchParam.page + '/' + searchParam.limit;
            let param = {
                condition: searchParam.condition,
                moduleId: searchParam.moduleId,
                projectId: this.$store.state.projectId
            };
            this.$post(url, param, response => {
                let data = response.data;
                for(let i=0;i<data.list.length;i++){
                    if(data.list[i].moduleId==='0'){
                        data.list[i].moduleName='默认模块';
                    }
                    data.list[i].updateTime = timestampToTime(data.list[i].updateTime);
                }
                this.apiListData = data.list;
                this.loading = false
                // 分页赋值
                this.pageParam.currentPage = this.searchForm.page;
                this.pageParam.pageSize = this.searchForm.limit;
                this.pageParam.total = data.total;
            });
        },
        // 分页插件事件
        callFather(param) {
            this.searchForm.page = param.currentPage
            this.searchForm.limit = param.pageSize
            this.getdata(this.searchForm)
        },
        // 搜索按钮
        search() {
            this.getdata(this.searchForm)
        },
        // 重置按钮
        reset() {
            this.searchForm.condition = "";
            this.searchForm.moduleId = "";
            this.getdata(this.searchForm);
        },
        importApi(){
          this.uploadFileVisible = true;
        },
        // 新增接口
        addApi(){
            this.$router.push({path: '/caseCenter/interfaceManage/add'})
        },
        // 编辑接口
        editApi(row){
            this.$router.push({path: '/caseCenter/interfaceManage/edit/' + row.id})
        },
        // 删除接口
        deleteApi(row){
            this.$confirm('确定要删除接口吗?', '删除提示', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'warning'
            })
            .then(() => {
                let url = '/autotest/api/delete';
                this.$post(url, {id: row.id}, response => {
                    this.$message.success("删除成功");
                    this.getdata(this.searchForm);
                });
            })
            .catch(() => {
                this.$message.success("取消成功");
            })
        },
        // 自动生成用例
        generateCase(row){
          this.paramRuleForm.apiId = row.id;
          this.paramRuleForm.header = [];
          this.paramRuleForm.body = [];
          this.paramRuleForm.query = [];
          this.paramRuleForm.rest = [];
          this.paramRuleForm.positiveAssertion = [];
          this.paramRuleForm.oppositeAssertion = [];
          this.editRuleVisible = true;
        },
        // 提交自动用例规则
        submitRuleForm(form){
          if(form.positiveAssertion.length === 0 | form.oppositeAssertion.length === 0){
            this.$message.warning("请至少维护一条正向断言以及逆向断言");
            return;
          }
          let url = '/autotest/case/auto/generate';
          this.$post(url, form, response => {
              this.$message.success("生成成功 前往用例管理页查看");
              this.editRuleVisible = false;
          });
        }
    }
}
</script>

<style scoped>
.left-tree {
    padding-right: 5px;
    border-right:1px solid rgb(219, 219, 219);
}
.right-table {
    padding-left: 5px;
}
.api-drawer-header{
    border-bottom: 1px solid rgb(219, 219, 219); 
    height: 42px; 
    display: flex; 
    justify-content: space-between;
    align-items: center;
    padding: 0px 20px;
}
.api-drawer-body{
    padding: 10px 20px;
}
</style>
