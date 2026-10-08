package xyz.leedaud.echo.storage;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {StoredRecord.class}, version = 1, exportSchema = true)
public abstract class EchoDatabase extends RoomDatabase {
    public abstract RecordDao records();
}
