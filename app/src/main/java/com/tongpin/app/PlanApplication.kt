package com.tongpin.app

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Recovery runs before any data screen or timer component can write to an interrupted restore. */
class PlanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlanRecovery.initialize(this)
    }
}

object PlanRecovery {
    var failure by mutableStateOf<String?>(null)

    fun initialize(context: Context) {
        if(File(context.filesDir,"complete-restore").exists())
            failure="上次完整恢复尚未收尾，正在检查安全副本。"
    }
}

@Composable fun RecoveryGate() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var working by remember{mutableStateOf(false)}
    fun recover(){
        if(working)return
        working=true
        scope.launch(NonCancellable){
            val result=withContext(Dispatchers.IO){runCatching{CompleteBackupStore(context.applicationContext).recoverInterrupted()}}
            PlanRecovery.failure=result.exceptionOrNull()?.let{"未能完成恢复：${it.message ?: "存储暂时不可用"}。安全副本仍保留，请检查可用空间后重试。"}
            working=false
        }
    }
    LaunchedEffect(Unit){recover()}
    Surface(Modifier.fillMaxSize(),color=Paper){
        PlanAlertDialog(onDismissRequest={},title={Text(if(working)"正在恢复数据" else "数据恢复需要继续")},text={
            Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
                if(working)CircularProgressIndicator()
                Text(if(working)"正在检查并完成上次的数据恢复，请稍候。" else PlanRecovery.failure.orEmpty())
            }
        },confirmButton={if(!working)TextButton({recover()}){Text("重试恢复")}},dismissButton={
            if(!working)TextButton({(context as? ComponentActivity)?.finish()}){Text("关闭应用")}
        })
    }
}
