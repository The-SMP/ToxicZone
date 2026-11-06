# ToxicZone

Paper 毒圈插件，支援 Minecraft 1.21.11 至 26.3。Paper 26.1 以上需要 Java 25。

## 安裝

將 ToxicZone.jar 和 HttpHub 放進 plugins 資料夾後重啟。插件名稱與資料夾統一為 ToxicZone。
首次升級時，會從舊的 plugins/AuroraToxicZone 複製尚未建立的設定和資源包。
既有 ToxicZone 資料夾中的檔案優先使用。

資源包透過 HttpHub 提供，請在 HttpHub 設定主機分配且對外開放的 TCP 埠。
資源包位於 plugins/ToxicZone/resource-pack.zip。

## 場地與面板

輸入 /tz 開啟自己的場地面板，左上角箱子可以建立、選取場地。
面板、刪除確認和選區釣竿都綁定自己的目標場地。切換場地後，舊場地的釣竿會阻止選區並提示重新領取。切換場地會清除尚未完成的選區。
面板只接受左鍵操作，釣竿使用左鍵第一角、右鍵第二角。

每個場地有獨立的中心、傷害外框、起始半徑、最小半徑、縮圈時間、傷害與間隔。
只有在場地外框內且在圓形安全區外的存活玩家會受傷。
場地縮至最小半徑後，圓圈與傷害持續生效，直到管理員手動停止或刪除。
同時運作的場地外框不能重疊。

開始時間與淘汰玩家會保存。重啟或 reload 後接續進度，停機時間計入縮圈時間。
舊版沒有保存開始時間的場次需重新開始。
未綁定場地的舊釣竿請從面板重新取得。

## 指令

玩家省略場地時使用自己選取的場地。控制台和指令方塊需使用明確場地名稱。

| 指令 | 用途 |
| --- | --- |
| /tz | 開啟面板 |
| /tz help | 全部指令 |
| /tz arena list | 場地清單 |
| /tz arena create <名稱> | 在目前位置建立場地 |
| /tz arena use <名稱> | 選取自己的操作場地 |
| /tz arena delete <名稱> confirm | 刪除場地 |
| /tz [場地] status | 查看狀態 |
| /tz [場地] start | 開始並顯示目標場地名稱 |
| /tz [場地] stop | 停止傷害與縮圈 |
| /tz [場地] center [世界 x z] | 設定中心 |
| /tz [場地] wand | 取得綁定場地的釣竿 |
| /tz [場地] radius <起始> [最小] | 設定半徑 |
| /tz [場地] time <秒數> | 設定縮圈時間 |
| /tz [場地] damage <傷害> [間隔秒數] | 設定傷害 |
| /tz reload | 重載設定、訊息與粒子排程 |
| /tz version | 檢查版本 |

所有指令限 OP 或 auroratoxiczone.admin 權限使用，版本檢查另需 auroratoxiczone.version。
名稱使用小寫英數、底線或減號，最多 32 字元，不能與主指令同名。
Tab 會補上場地、世界、座標、數值範例和刪除確認參數。

## 設定與版本

config.yml 缺少項目會補齊。reload 會套用粒子顯示間隔。
settings.update-notifications 控制管理員登入的更新提醒。
版本檢查直接使用 catdevteam/ToxicZone，啟動檢查一次，結果快取 10 分鐘。
主控台會顯示版本比較結果。Release 標籤採用 vA.B.C。

所有訊息在 messages.yml。更新時會替換與前版預設相同的訊息，保留自訂訊息。
面板說明會自動折行。

## 建置

使用 Java 21 與 Maven，執行 mvn clean verify。
編譯檔為 target/toxic-zone-1.3.1.jar，發布附件名稱為 ToxicZone.jar。
