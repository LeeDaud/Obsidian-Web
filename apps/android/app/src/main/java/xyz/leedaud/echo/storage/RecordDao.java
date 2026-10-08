package xyz.leedaud.echo.storage;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface RecordDao {
    @Query("SELECT * FROM records WHERE id = :id") StoredRecord find(String id);
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY id") List<StoredRecord> list(String kind);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void put(StoredRecord record);
}
