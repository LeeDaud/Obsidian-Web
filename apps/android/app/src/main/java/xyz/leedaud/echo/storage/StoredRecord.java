package xyz.leedaud.echo.storage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "records", indices = {@Index("kind")})
public class StoredRecord {
    @PrimaryKey @NonNull public String id;
    @NonNull public String kind;
    @NonNull public String payload;
    public long version;
    public StoredRecord(@NonNull String id, @NonNull String kind, @NonNull String payload, long version) {
        this.id = id; this.kind = kind; this.payload = payload; this.version = version;
    }
}
