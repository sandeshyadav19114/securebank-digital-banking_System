package com.securebank.transaction;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Append-only: save + reads only. */
public interface TransactionRepository extends Repository<BankTransaction, Long> {

    <S extends BankTransaction> S saveAndFlush(S entity);

    Optional<BankTransaction> findByReference(String reference);

    Optional<BankTransaction> findByIdempotencyKey(String idempotencyKey);

    @Query("select t from BankTransaction t where t.fromAccount = :acc or t.toAccount = :acc order by t.id desc")
    Page<BankTransaction> findByAccount(@Param("acc") String accountNumber, Pageable pageable);
}
