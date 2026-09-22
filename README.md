## OrzRepacker

### 使用方法
**全新安装**
1. 下载 [Environment.7z](https://github.com/leevccc/orange-wz/releases/download/v1.155.47/Environment.7z)
2. 到 [Release](https://github.com/leevccc/orange-wz/releases) 下载最新的 OrzRepacker-version.7z
3. 将两个压缩包里的文件解压后放在同一个文件夹中
4. 运行 OrzRepacker.exe

**版本升级**
1. 到 [Release](https://github.com/leevccc/orange-wz/releases) 下载最新的 OrzRepacker-version.7z
2. 解压后将 OrzRepacker.exe 和 data.bin 替换到原来的目录即可

### I18n 多语言配置方法
创建 config.ini 文件
```ini
# zh_CN / en_US
language = zh_CN
```

### MCP连接方式

将配置里的
`spring.main.web-application-type=none`
改为
`spring.main.web-application-type=servlet`
启用web端口

本地默认开启10002端口

参考配置

```json
{
  "mcpServers": {
    "orange-wz": {
      "url": "http://127.0.0.1:10002/mcp"
    }
  }
}
```

