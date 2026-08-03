package pl.najem.acc.application;

import java.time.LocalDate;
import java.util.List;

public interface BankStatementPort {

    List<BankLine> fetchSince(LocalDate since);
}
