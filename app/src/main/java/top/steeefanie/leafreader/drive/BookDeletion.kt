package top.steeefanie.leafreader.drive

import androidx.room.withTransaction
import top.steeefanie.leafreader.storage.ReaderDatabase

/** Preserve offline history and stop automatic uploads until an explicit cloud restore. */
suspend fun recordBookDeletion(db: ReaderDatabase, owner: String, hash: String, time: Long) {
    db.withTransaction {
        db.drive().putDeletion(DeletedCloudBook(owner, hash, time))
        db.drive().putLocalOnly(LocalOnlyBook(owner, hash))
        db.drive().stopDeletedSync(owner, hash)
        db.drive().removeConflicts(owner, hash)
        db.drive().markBookDeleted(owner, hash)
    }
}
