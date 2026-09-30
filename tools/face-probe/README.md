# 独立面容服务原型

这个原型由 root `app_process` 进程直接调用设备的私有 `IFaceService.authenticate`，只输出认证成功、失败或超时。它不加载 BioPay、不读取或填写支付密码，也不更改 LSPosed 作用域。

在已连接的测试设备上，`getSensorPropertiesInternal` 返回一个面容传感器，`authenticate` 发起请求后收到真实的 `onAuthenticationSucceeded` 回调。该设备当时报告的面容强度是 `255`（Class 2）；**尚未在原始 Class 1 状态下验证**。

## 构建和运行

需要 Android SDK 35、JDK、ADB 和手机 root。下面的命令从仓库根目录执行：

```sh
mkdir -p /tmp/biopay-face-probe-build/classes
javac -cp "$ANDROID_HOME/platforms/android-35/android.jar" \
  -d /tmp/biopay-face-probe-build/classes tools/face-probe/FaceServiceProbe.java
jar cf /tmp/biopay-face-probe-build/classes.jar \
  -C /tmp/biopay-face-probe-build/classes .
"$ANDROID_HOME/build-tools/35.0.0/d8" \
  --lib "$ANDROID_HOME/platforms/android-35/android.jar" --min-api 28 \
  --output /tmp/biopay-face-probe-build/probe.jar \
  /tmp/biopay-face-probe-build/classes.jar
adb push /tmp/biopay-face-probe-build/probe.jar /data/local/tmp/biopay-face-probe.jar
adb shell su -c 'CLASSPATH=/data/local/tmp/biopay-face-probe.jar app_process /system/bin FaceServiceProbe inspect'
adb shell su -c 'CLASSPATH=/data/local/tmp/biopay-face-probe.jar app_process /system/bin FaceServiceProbe auth'
```

`inspect` 只读取接口签名；`auth` 发起一次最长 20 秒的认证，超时后取消。运行 `auth` 时保持屏幕点亮并面对摄像头。这个原型当前假定设备只有一个面容传感器，且目标用户为 Android 用户 0。

## 接入 BioPay 前还需解决

- 在传感器未被提升等级的 Class 1 设备上重做实机验证，确认厂商 FaceService 接受直接认证请求。
- 建立有身份校验的 App 与 root 辅助进程通信；不能把辅助进程的可调用接口开放给其他应用。
- 将每次认证与请求方、当前支付会话和超时绑定，并处理取消、进程退出和屏幕状态。
- 确认认证结果对 BioPay 现有密钥与密码使用策略的影响，再决定是否接入支付流程。

私有接口可能随 Android 或厂商系统升级变化，原型使用运行时反射以便先验证设备行为。
