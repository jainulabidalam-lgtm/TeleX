package com.e2eechat.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats")
data class ChatEntity(
    @androidx.room.PrimaryKey val id: String,
    val contactName: String,
    val contactInitials: String,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int,
    val isOnline: Boolean,
    val avatarColorHex: Long,
    val lastSeenText: String
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index("chatId")]
)
data class MessageEntity(
    @androidx.room.PrimaryKey val id: String,
    val chatId: String,
    val content: String,
    val timestamp: String,
    val isSentByMe: Boolean,
    val isRead: Boolean,
    val imageUrl: String? = null,
    val attachmentDescriptor: String? = null
) {
    override fun toString(): String {
        val descStr = if (attachmentDescriptor != null) "[DESCRIPTOR_REDACTED]" else "null"
        return "MessageEntity(id=$id, chatId=$chatId, content=$content, timestamp=$timestamp, isSentByMe=$isSentByMe, isRead=$isRead, imageUrl=$imageUrl, attachmentDescriptor=$descStr)"
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY rowid")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun findById(chatId: String): ChatEntity?

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun getChatCount(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY rowid")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId")
    suspend fun getMessagesForChatList(chatId: String): List<MessageEntity>

    @Query("SELECT content FROM messages WHERE id = :id LIMIT 1")
    suspend fun getPlaintextById(id: String): String?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :id")
    suspend fun updateMessageStatus(id: String, isRead: Boolean)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("UPDATE messages SET imageUrl = :imageUrl WHERE id = :id")
    suspend fun updateMessageImageUrl(id: String, imageUrl: String)

    @Query("SELECT attachmentDescriptor FROM messages WHERE id = :id LIMIT 1")
    suspend fun getAttachmentDescriptorById(id: String): String?

    @Query("UPDATE messages SET attachmentDescriptor = :descriptor WHERE id = :id")
    suspend fun updateAttachmentDescriptor(id: String, descriptor: String?)

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND attachmentDescriptor IS NOT NULL AND (imageUrl IS NULL OR imageUrl = '')")
    suspend fun getPendingAttachmentMessages(chatId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN imageUrl TEXT DEFAULT NULL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentDescriptor TEXT DEFAULT NULL")
    }
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "e2eechat.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
            }
    }
}