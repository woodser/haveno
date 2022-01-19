/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */
package bisq.daemon.grpc;

import bisq.core.api.CoreApi;

import bisq.proto.grpc.AccountExistsReply;
import bisq.proto.grpc.AccountExistsRequest;
import bisq.proto.grpc.BackupAccountReply;
import bisq.proto.grpc.BackupAccountRequest;
import bisq.proto.grpc.ChangePasswordReply;
import bisq.proto.grpc.ChangePasswordRequest;
import bisq.proto.grpc.CloseAccountReply;
import bisq.proto.grpc.CloseAccountRequest;
import bisq.proto.grpc.CreateAccountReply;
import bisq.proto.grpc.CreateAccountRequest;
import bisq.proto.grpc.DeleteAccountReply;
import bisq.proto.grpc.DeleteAccountRequest;
import bisq.proto.grpc.IsAccountOpenReply;
import bisq.proto.grpc.IsAccountOpenRequest;
import bisq.proto.grpc.OpenAccountReply;
import bisq.proto.grpc.OpenAccountRequest;
import bisq.proto.grpc.RestoreAccountReply;
import bisq.proto.grpc.RestoreAccountRequest;

import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import com.google.protobuf.ByteString;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.util.HashMap;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static bisq.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static bisq.proto.grpc.AccountGrpc.*;
import static java.util.concurrent.TimeUnit.SECONDS;

import bisq.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import bisq.daemon.grpc.interceptor.GrpcCallRateMeter;

@VisibleForTesting
@Slf4j
public class GrpcAccountService extends AccountImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    // Temp in memory stream for restoring account.
    private ByteArrayOutputStream tempStream;

    @Inject
    public GrpcAccountService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void accountExists(AccountExistsRequest req, StreamObserver<AccountExistsReply> responseObserver) {
        try {
            var reply = AccountExistsReply.newBuilder()
                    .setAccountExists(coreApi.accountExists())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void backupAccount(BackupAccountRequest req, StreamObserver<BackupAccountReply> responseObserver) {

        // Send in large chunks to reduce unnecessary overhead. Typical backup will not be more than a few MB.
        // From current testing it appears that client gRPC-web is slow in processing the bytes on download.
        try {
            int bufferSize = 1024 * 1024 * 8;
            coreApi.backupAccount(bufferSize, (stream) -> {
                try {
                    log.info("Sending bytes in chunks of: " + bufferSize);
                    byte[] buffer = new byte[bufferSize];
                    int length;
                    int total = 0;
                    while ((length = stream.read(buffer, 0, bufferSize)) != -1) {
                        log.info("Chunk size: " + length);
                        total += length;
                        var reply = BackupAccountReply.newBuilder()
                                .setZipBytes(ByteString.copyFrom(buffer, 0, length))
                                .build();
                        responseObserver.onNext(reply);
                    }
                    log.info("Completed backup account total sent: " + total);
                    stream.close();
                    responseObserver.onCompleted();
                } catch (Exception ex) {
                    exceptionHandler.handleException(log, ex, responseObserver);
                }
            }, (ex) -> exceptionHandler.handleException(log, ex, responseObserver));
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void changePassword(ChangePasswordRequest req, StreamObserver<ChangePasswordReply> responseObserver) {
        try {
            coreApi.changePassword(req.getPassword());
            var reply = ChangePasswordReply.newBuilder()
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void closeAccount(CloseAccountRequest req, StreamObserver<CloseAccountReply> responseObserver) {
        try {
            coreApi.closeAccount();
            var reply = CloseAccountReply.newBuilder()
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void createAccount(CreateAccountRequest req, StreamObserver<CreateAccountReply> responseObserver) {
        try {
            coreApi.createAccount(req.getPassword());
            var reply = CreateAccountReply.newBuilder()
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void deleteAccount(DeleteAccountRequest req, StreamObserver<DeleteAccountReply> responseObserver) {
        try {
            log.warn("Got deleteAccount request");
            // Delete account requires a long shutdown, reply after its completed.
            coreApi.deleteAccount(() ->{
                var reply = DeleteAccountReply.newBuilder().build();
                log.info("DeleteAccount completed, replying to rpc client");
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
                log.info("DeleteAccount continuing to shutdown process");
            });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void isAccountOpen(IsAccountOpenRequest req, StreamObserver<IsAccountOpenReply> responseObserver) {
        try {
            var reply = IsAccountOpenReply.newBuilder()
                    .setIsAccountOpen(coreApi.isAccountOpen())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void openAccount(OpenAccountRequest req, StreamObserver<OpenAccountReply> responseObserver) {
        try {
            coreApi.openAccount(req.getPassword());
            var reply = OpenAccountReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void restoreAccount(RestoreAccountRequest req, StreamObserver<RestoreAccountReply> responseObserver) {
        try {
            // Fail fast since uploading and processing bytes takes resources.
            if (coreApi.accountExists()) {
                throw new IllegalStateException("Cannot restore account if there is an existing account");
            }

            // If the entire zip is in memory, no need to write to disk.
            // Restore the account directly from the zip stream.
            if (!req.getHasMore() && req.getOffset() == 0) {
                var inputStream = req.getZipBytes().newInput();
                coreApi.restoreAccount(inputStream, 1024 * 64, () -> {
                    var reply = RestoreAccountReply.newBuilder().build();
                    log.info("RestoreAccount completed, replying to rpc client");
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                    log.info("RestoreAccount continuing to shutdown process");
                });
            } else {
                if (req.getOffset() == 0) {
                    log.info("RestoreAccount starting new chunked zip");
                    tempStream = new ByteArrayOutputStream((int) req.getTotalLength());
                }
                if (tempStream.size() != req.getOffset()) {
                    log.warn("Stream offset doesn't match current position");
                    IllegalStateException cause = new IllegalStateException("Stream offset doesn't match current position");
                    exceptionHandler.handleException(log, cause, responseObserver);
                } else {
                    log.info("RestoreAccount writing chunk size " + req.getZipBytes().size());
                    req.getZipBytes().writeTo(tempStream);
                }

                if (!req.getHasMore()) {
                    var inputStream = new ByteArrayInputStream(tempStream.toByteArray());
                    tempStream.close();
                    tempStream = null;
                    coreApi.restoreAccount(inputStream, 1024 * 64, () -> {
                        var reply = RestoreAccountReply.newBuilder().build();
                        log.info("RestoreAccount completed, replying to rpc client");
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                        log.info("RestoreAccount continuing to shutdown process");
                    });
                } else {
                    var reply = RestoreAccountReply.newBuilder().build();
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                }
            }
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    final ServerInterceptor[] interceptors() {
        Optional<ServerInterceptor> rateMeteringInterceptor = rateMeteringInterceptor();
        return rateMeteringInterceptor.map(serverInterceptor ->
                new ServerInterceptor[]{serverInterceptor}).orElseGet(() -> new ServerInterceptor[0]);
    }

    final Optional<ServerInterceptor> rateMeteringInterceptor() {
        return getCustomRateMeteringInterceptor(coreApi.getConfig().appDataDir, this.getClass())
                .or(() -> Optional.of(CallRateMeteringInterceptor.valueOf(
                        new HashMap<>() {{
                            put(getAccountExistsMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getBackupAccountMethod().getFullMethodName(), new GrpcCallRateMeter(5, SECONDS));
                            put(getChangePasswordMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getCloseAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getCreateAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getDeleteAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getIsAccountOpenMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getOpenAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getRestoreAccountMethod().getFullMethodName(), new GrpcCallRateMeter(5, SECONDS));
                        }}
                )));
    }
}
