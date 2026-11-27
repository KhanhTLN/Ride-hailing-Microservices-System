package com.threecats.tripservice.worker;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Session lock fences workers without holding a transaction across an RPC. */
@Component @RequiredArgsConstructor
public class WorkerLock {
    private final DataSource dataSource;
    public void run(String key,Runnable work) {
        try(var connection=dataSource.getConnection()) {
            boolean acquired;
            try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,73003))")) {
                statement.setString(1,key);
                try(var result=statement.executeQuery()) { result.next(); acquired=result.getBoolean(1); }
            }
            if(!acquired) return;
            try { work.run(); }
            finally {
                try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,73003))")) { statement.setString(1,key); statement.execute(); }
            }
        } catch(java.sql.SQLException e) { throw new IllegalStateException("Worker lock connection failed",e); }
    }
}
