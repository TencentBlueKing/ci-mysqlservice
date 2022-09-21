package com.tencent.bk.devops.atom.task.pojo;

public class DockerRunPortBinding {
    private String hostIp;
    private Integer containerPort;
    private Integer hostPort;

    public DockerRunPortBinding() {

    }

    public DockerRunPortBinding(String hostIp, Integer containerPort, Integer hostPort) {
        this.hostIp = hostIp;
        this.containerPort = containerPort;
        this.hostPort = hostPort;
    }

    public String getHostIp() {
        return hostIp;
    }

    public void setHostIp(String hostIp) {
        this.hostIp = hostIp;
    }

    public Integer getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(Integer containerPort) {
        this.containerPort = containerPort;
    }

    public Integer getHostPort() {
        return hostPort;
    }

    public void setHostPort(Integer hostPort) {
        this.hostPort = hostPort;
    }
}
