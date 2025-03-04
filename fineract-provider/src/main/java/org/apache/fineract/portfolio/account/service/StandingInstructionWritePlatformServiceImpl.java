/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.service;

import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountTypeParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromClientIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountTypeParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.statusParamName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.RoutingDataSource;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.annotation.CronTarget;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDataValidator;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.AccountTransferStandingInstruction;
import org.apache.fineract.portfolio.account.domain.StandingInstructionAssembler;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.exception.StandingInstructionNotFoundException;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StandingInstructionWritePlatformServiceImpl implements StandingInstructionWritePlatformService {

    private static final Logger LOG = LoggerFactory.getLogger(StandingInstructionWritePlatformServiceImpl.class);

    private final StandingInstructionDataValidator standingInstructionDataValidator;
    private final StandingInstructionAssembler standingInstructionAssembler;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final StandingInstructionRepository standingInstructionRepository;
    private final StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;

    @Autowired
    public StandingInstructionWritePlatformServiceImpl(final StandingInstructionDataValidator standingInstructionDataValidator,
            final StandingInstructionAssembler standingInstructionAssembler,
            final AccountTransferDetailRepository accountTransferDetailRepository,
            final StandingInstructionRepository standingInstructionRepository,
            final StandingInstructionReadPlatformService standingInstructionReadPlatformService,
            final AccountTransfersWritePlatformService accountTransfersWritePlatformService, final RoutingDataSource dataSource,
            DatabaseSpecificSQLGenerator sqlGenerator) {
        this.standingInstructionDataValidator = standingInstructionDataValidator;
        this.standingInstructionAssembler = standingInstructionAssembler;
        this.accountTransferDetailRepository = accountTransferDetailRepository;
        this.standingInstructionRepository = standingInstructionRepository;
        this.standingInstructionReadPlatformService = standingInstructionReadPlatformService;
        this.accountTransfersWritePlatformService = accountTransfersWritePlatformService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.sqlGenerator = sqlGenerator;
    }

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {

        this.standingInstructionDataValidator.validateForCreate(command);

        final Integer fromAccountTypeId = command.integerValueSansLocaleOfParameterNamed(fromAccountTypeParamName);
        final PortfolioAccountType fromAccountType = PortfolioAccountType.fromInt(fromAccountTypeId);

        final Integer toAccountTypeId = command.integerValueSansLocaleOfParameterNamed(toAccountTypeParamName);
        final PortfolioAccountType toAccountType = PortfolioAccountType.fromInt(toAccountTypeId);

        final Long fromClientId = command.longValueOfParameterNamed(fromClientIdParamName);

        Long standingInstructionId = null;
        try {
            if (isSavingsToSavingsAccountTransfer(fromAccountType, toAccountType)) {
                final AccountTransferDetails standingInstruction = this.standingInstructionAssembler
                        .assembleSavingsToSavingsTransfer(command);
                this.accountTransferDetailRepository.save(standingInstruction);
                standingInstructionId = standingInstruction.accountTransferStandingInstruction().getId();
            } else if (isSavingsToLoanAccountTransfer(fromAccountType, toAccountType)) {
                final AccountTransferDetails standingInstruction = this.standingInstructionAssembler.assembleSavingsToLoanTransfer(command);
                this.accountTransferDetailRepository.save(standingInstruction);
                standingInstructionId = standingInstruction.accountTransferStandingInstruction().getId();
            } else if (isLoanToSavingsAccountTransfer(fromAccountType, toAccountType)) {

                final AccountTransferDetails standingInstruction = this.standingInstructionAssembler.assembleLoanToSavingsTransfer(command);
                this.accountTransferDetailRepository.save(standingInstruction);
                standingInstructionId = standingInstruction.accountTransferStandingInstruction().getId();

            }
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            final Throwable throwable = dve.getMostSpecificCause();
            handleDataIntegrityIssues(command, throwable, dve);
            return CommandProcessingResult.empty();
        }
        final CommandProcessingResultBuilder builder = new CommandProcessingResultBuilder().withEntityId(standingInstructionId)
                .withClientId(fromClientId);
        return builder.build();
    }

    private void handleDataIntegrityIssues(final JsonCommand command, Throwable realCause, final NonTransientDataAccessException dve) {

        if (realCause.getMessage().contains("name")) {
            final String name = command.stringValueOfParameterNamed(StandingInstructionApiConstants.nameParamName);
            throw new PlatformDataIntegrityException("error.msg.standinginstruction.duplicate.name",
                    "Standinginstruction with name `" + name + "` already exists", "name", name);
        }
        LOG.error("Error occured.", dve);
        throw new PlatformDataIntegrityException("error.msg.client.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource.");
    }

    private boolean isLoanToSavingsAccountTransfer(final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        return fromAccountType.isLoanAccount() && toAccountType.isSavingsAccount();
    }

    private boolean isSavingsToLoanAccountTransfer(final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        return fromAccountType.isSavingsAccount() && toAccountType.isLoanAccount();
    }

    private boolean isSavingsToSavingsAccountTransfer(final PortfolioAccountType fromAccountType,
            final PortfolioAccountType toAccountType) {
        return fromAccountType.isSavingsAccount() && toAccountType.isSavingsAccount();
    }

    @Override
    public CommandProcessingResult update(final Long id, final JsonCommand command) {
        this.standingInstructionDataValidator.validateForUpdate(command);
        AccountTransferStandingInstruction standingInstructionsForUpdate = this.standingInstructionRepository.findById(id)
                .orElseThrow(() -> new StandingInstructionNotFoundException(id));
        final Map<String, Object> actualChanges = standingInstructionsForUpdate.update(command);
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(id).with(actualChanges).build();
    }

    @Override
    public CommandProcessingResult delete(final Long id) {
        AccountTransferStandingInstruction standingInstructionsForUpdate = this.standingInstructionRepository.findById(id).get();
        // update the "deleted" and "name" properties of the standing
        // instruction
        standingInstructionsForUpdate.delete();

        final Map<String, Object> actualChanges = new HashMap<>();
        actualChanges.put(statusParamName, StandingInstructionStatus.DELETED.getValue());
        return new CommandProcessingResultBuilder().withEntityId(id).with(actualChanges).build();
    }

    @Override
    @CronTarget(jobName = JobName.EXECUTE_STANDING_INSTRUCTIONS)
    public void executeStandingInstructions() throws JobExecutionException {
        Collection<StandingInstructionData> instructionDatas = this.standingInstructionReadPlatformService
                .retrieveAll(StandingInstructionStatus.ACTIVE.getValue());
        List<Throwable> errors = new ArrayList<>();
        for (StandingInstructionData data : instructionDatas) {
            boolean isDueForTransfer = false;
            AccountTransferRecurrenceType recurrenceType = data.recurrenceType();
            StandingInstructionType instructionType = data.instructionType();
            LocalDate transactionDate = LocalDate.now(DateUtils.getDateTimeZoneOfTenant());
            if (recurrenceType.isPeriodicRecurrence()) {
                final ScheduledDateGenerator scheduledDateGenerator = new DefaultScheduledDateGenerator();
                PeriodFrequencyType frequencyType = data.recurrenceFrequency();
                LocalDate startDate = data.validFrom();
                if (frequencyType.isMonthly()) {
                    startDate = startDate.withDayOfMonth(data.recurrenceOnDay());
                    if (startDate.isBefore(data.validFrom())) {
                        startDate = startDate.plusMonths(1);
                    }
                } else if (frequencyType.isYearly()) {
                    startDate = startDate.withDayOfMonth(data.recurrenceOnDay()).withMonth(data.recurrenceOnMonth());
                    if (startDate.isBefore(data.validFrom())) {
                        startDate = startDate.plusYears(1);
                    }
                }
                isDueForTransfer = scheduledDateGenerator.isDateFallsInSchedule(frequencyType, data.recurrenceInterval(), startDate,
                        transactionDate);

            }
            BigDecimal transactionAmount = data.amount();
            if (data.toAccountType().isLoanAccount()
                    && (recurrenceType.isDuesRecurrence() || (isDueForTransfer && instructionType.isDuesAmoutTransfer()))) {
                StandingInstructionDuesData standingInstructionDuesData = this.standingInstructionReadPlatformService
                        .retriveLoanDuesData(data.toAccount().accountId());
                if (data.instructionType().isDuesAmoutTransfer()) {
                    transactionAmount = standingInstructionDuesData.totalDueAmount();
                }
                if (recurrenceType.isDuesRecurrence()) {
                    isDueForTransfer = LocalDate.now(DateUtils.getDateTimeZoneOfTenant()).equals(standingInstructionDuesData.dueDate());
                }
            }

            if (isDueForTransfer && transactionAmount != null && transactionAmount.compareTo(BigDecimal.ZERO) > 0) {
                final SavingsAccount fromSavingsAccount = null;
                final boolean isRegularTransaction = true;
                final boolean isExceptionForBalanceCheck = false;
                AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, transactionAmount, data.fromAccountType(),
                        data.toAccountType(), data.fromAccount().accountId(), data.toAccount().accountId(),
                        data.name() + " Standing instruction trasfer ", null, null, null, null, data.toTransferType(), null, null,
                        data.transferType().getValue(), null, null, null, null, null, fromSavingsAccount, isRegularTransaction,
                        isExceptionForBalanceCheck);
                final boolean transferCompleted = transferAmount(errors, accountTransferDTO, data.getId());

                if (transferCompleted) {
                    final String updateQuery = "UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?";
                    this.jdbcTemplate.update(updateQuery, Date.from(transactionDate.atStartOfDay(ZoneId.systemDefault()).toInstant()),
                            data.getId());
                }

            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
    }

    private boolean transferAmount(final List<Throwable> errors, final AccountTransferDTO accountTransferDTO, final Long instructionId) {
        boolean transferCompleted = true;
        StringBuilder errorLog = new StringBuilder();
        StringBuilder updateQuery = new StringBuilder(
                "INSERT INTO m_account_transfer_standing_instructions_history (standing_instruction_id, " + sqlGenerator.escape("status")
                        + ", amount,execution_time, error_log) VALUES (");
        try {
            this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
        } catch (final PlatformApiDataValidationException e) {
            errors.add(new Exception("Validation exception while transfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Validation exception while trasfering funds " + e.getDefaultUserMessage());
        } catch (final InsufficientAccountBalanceException e) {
            errors.add(new Exception("InsufficientAccountBalance Exception while trasfering funds for standing Instruction id"
                    + instructionId + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("InsufficientAccountBalance Exception ");
        } catch (final AbstractPlatformServiceUnavailableException e) {
            errors.add(new Exception("Platform exception while trasfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Platform exception while trasfering funds " + e.getDefaultUserMessage());
        } catch (Exception e) {
            errors.add(new Exception("Unhandled System Exception while trasfering funds for standing Instruction id" + instructionId
                    + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Exception while trasfering funds " + e.getMessage());

        }
        updateQuery.append(instructionId).append(",");
        if (errorLog.length() > 0) {
            transferCompleted = false;
            updateQuery.append("'failed'").append(",");
        } else {
            updateQuery.append("'success'").append(",");
        }
        updateQuery.append(accountTransferDTO.getTransactionAmount().doubleValue());
        updateQuery.append(", now(),");
        updateQuery.append("'").append(errorLog.toString()).append("')");
        this.jdbcTemplate.update(updateQuery.toString());
        return transferCompleted;
    }
}
