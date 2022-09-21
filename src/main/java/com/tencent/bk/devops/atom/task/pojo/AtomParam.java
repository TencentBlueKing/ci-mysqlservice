package com.tencent.bk.devops.atom.task.pojo;

import com.tencent.bk.devops.atom.pojo.AtomBaseParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 插件参数定义
 * @version 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AtomParam extends AtomBaseParam {
    /**
     * 以下请求参数只是示例，具体可以删除修改成你要的参数
     */
    private String imageName; //描述信息
    private String port; //描述信息
    private String mysqlPw; //描述信息
    private String initCmd;
}
