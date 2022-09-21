## 插件功能
提供mysql容器服务。
用户可以选择镜像以及自定义密码登录，在脚本内登录示例：
mysql -h ${MYSQL_IP} -P ${MYSQL_PORT} -uroot -pXXXX -e “select NOW()”

## 适用场景
依赖mysql的测试场景
依赖mysql的编译场景
## 使用限制和受限解决方案[可选]
mysql用户名默认：root
mysql端口默认：3306
