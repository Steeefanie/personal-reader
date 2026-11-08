package top.steeefanie.leafreader.ui

import android.content.Context

object BookRemoval {
    const val DELETE = "删除"
    const val KEEP_LOCAL = "仅保留在本地"
    const val UPLOAD = "上传至云端"
    const val LOCAL = "仅移除本地副本"
    const val CLOUD_ONLY = "仅移除云端副本"
    const val CLOUD = "从本地和云端删除"

    fun confirmDelete(context: Context, title: String, remove: () -> Unit) {
        PaperPanel.Builder(context).setTitle("删除这本书？")
            .setMessage("《$title》\n\n从本机删除这本书。阅读进度和书签继续保留，重新导入同一文件可恢复。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> remove() }.show()
    }
    fun confirmLocal(context: Context, title: String, remove: () -> Unit) {
        PaperPanel.Builder(context).setTitle("仅移除本地副本？")
            .setMessage("《$title》\n\n只移除当前空间中的本机书籍副本。云端文件、阅读进度和书签继续保留，可在云盘页重新下载。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ -> remove() }.show()
    }
    fun confirmCloudOnly(context: Context, title: String, remove: () -> Unit) {
        PaperPanel.Builder(context).setTitle("仅移除云端副本？")
            .setMessage("《$title》\n\n删除这一个云端书籍文件及它对应的进度 JSON（含云端书签）。本机副本和本机阅读记录保留，之后这本书设为“仅保留在本地”。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ -> remove() }.show()
    }
    fun confirmCloud(context: Context, title: String, remove: () -> Unit) {
        PaperPanel.Builder(context).setTitle("从本地和云端删除？")
            .setMessage("《$title》\n\n删除这一个云端书籍文件及它对应的进度 JSON（含云端书签），确认成功后再移除本机副本。此操作无法撤销。\n\n云端删除失败或未能确认结果时，本机副本会保留。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> remove() }.show()
    }
}
