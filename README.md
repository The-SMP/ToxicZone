# ToxicZone

Paper 1.21.11 至 26.3 毒圈插件，最低 API 編譯版本為 1.21.11，Java 21 bytecode，Maven 建置，初版 1.0.0。Paper 26.1 以上伺服器執行時需使用 Java 25。

## 建置與安裝

在本目錄執行 `mvn package`，將 `target/toxic-zone-1.0.0.jar` 放入伺服器 `plugins` 資料夾後重啟。第一次啟動會建立 `config.yml` 與 `messages.yml`。

## 指令

直接輸入 `/tz` 會開啟毒圈控制箱子面板；面板只使用左鍵操作，並由自訂 `InventoryHolder` 標記來源。管理權限為 `auroratoxiczone.admin`（預設 OP）。其他管理指令使用 `/toxiczone`（別名 `/tz`）。

- `/tz status` 查看狀態
- `/tz center` 將中心設為玩家目前世界與 X/Z；`/tz center <世界> <x> <z>` 可在任意位置指定
- `/tz wand` 取得特殊區域選取胡蘿蔔釣竿；左鍵選第一角、右鍵選第二角，完成後自動計算中心與可容納在選區內的圓形半徑。選區高度不影響水平毒圈。毒圈未開始或停止時會以粒子顯示方形選區邊緣
- `/tz radius <起始半徑> [最小半徑]`
- `/tz time <秒數>` 設定完整縮圈時間
- `/tz damage <傷害值> [間隔秒數]`
- `/tz start`、`/tz stop`
- `/tz reload` 補齊並重載兩份設定
- `/tz version` 檢查 GitHub Releases，需 `auroratoxiczone.version`（預設 OP）

預設每秒造成 3 點傷害，即 1.5 格紅心。玩家所在世界須與毒圈設定世界相同；距中心 X/Z 的水平距離超出動態半徑時受傷。半徑依開始半徑至最小半徑線性縮小，達到設定時間後會自動停止傷害與粒子效果。邊界預設顯示 12 格高、每約 3 格一列的亮綠色粒子牆，距離玩家 64 格內可見；中心、半徑、時間、傷害均可在遊戲內改動。

玩家死亡後會在該場維持淘汰狀態，重生、重新登入或伺服器重啟都不會再受到毒圈傷害；管理員下次執行 `/tz start` 時才重設淘汰狀態。

插件預設使用 `The-SMP/ToxicZone` 檢查 GitHub Releases；結果快取 10 分鐘，沒有定時輪詢。有更新時，具有版本權限的管理員登入 3 秒後收到提醒。若使用其他儲存庫，可將 `settings.github-repository` 設為 `owner/repository`。
