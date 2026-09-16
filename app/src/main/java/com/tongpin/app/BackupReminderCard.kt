package com.tongpin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable fun BackupReminderCard(onBackup: () -> Unit, onLater: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color=Apricot,shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Backup,null)
                Text("为这段积累留一份备份",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            }
            Text("已有一周未导出完整备份。保存一份文件，换手机时也能找回计划和记录。",style=MaterialTheme.typography.bodySmall,color=Muted)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onBackup){Text("去备份")}
                Box {
                    TextButton({menu=true}){Text("稍后提醒")}
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("明天提醒")},onClick={menu=false;onLater(1)})
                        DropdownMenuItem(text={Text("7 天后提醒")},onClick={menu=false;onLater(7)})
                    }
                }
            }
        }
    }
}
