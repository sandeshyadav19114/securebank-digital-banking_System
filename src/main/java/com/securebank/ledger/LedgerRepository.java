package com.securebank.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Append-only: only inserts and reads are exposed, there is intentionally no update/delete method. */
public interface LedgerRepository extends Repository<LedgerEntry, Long> {

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    List<LedgerEntry> findByAccountNumberAndEntryDateBetweenOrderByIdAsc(String accountNumber, LocalDate from, LocalDate to);

    @Query("select sum(l.amount) from LedgerEntry l where l.accountNumber = :acc and l.entryType = :type and l.entryDate < :date")
    BigDecimal sumBefore(@Param("acc") String accountNumber, @Param("type") EntryType type, @Param("date") LocalDate date);

    @Query("select sum(l.amount) from LedgerEntry l where l.entryType = :type and l.entryDate = :date")
    BigDecimal sumForDate(@Param("type") EntryType type, @Param("date") LocalDate date);

    @Query("select l.accountNumber, l.entryType, sum(l.amount) from LedgerEntry l group by l.accountNumber, l.entryType")
    List<Object[]> totalsByAccount();

    @Query("select l.transactionId from LedgerEntry l where l.entryDate = :date group by l.transactionId "
            + "having sum(case when l.entryType = com.securebank.ledger.EntryType.DEBIT then l.amount else -l.amount end) <> 0")
    List<Long> findUnbalancedTransactions(@Param("date") LocalDate date);
}
