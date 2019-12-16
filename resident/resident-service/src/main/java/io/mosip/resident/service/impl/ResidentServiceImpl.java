package io.mosip.resident.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import io.mosip.kernel.core.idvalidator.spi.RidValidator;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.resident.config.LoggerConfiguration;
import io.mosip.resident.constant.ApiName;
import io.mosip.resident.constant.AuthTypeStatus;
import io.mosip.resident.constant.IdType;
import io.mosip.resident.constant.LoggerFileConstant;
import io.mosip.resident.constant.NotificationTemplateCode;
import io.mosip.resident.constant.RegistrationExternalStatusCode;
import io.mosip.resident.constant.ResidentErrorCode;
import io.mosip.resident.dto.AuthHistoryRequestDTO;
import io.mosip.resident.dto.AuthHistoryResponseDTO;
import io.mosip.resident.dto.AuthLockOrUnLockRequestDto;
import io.mosip.resident.dto.AuthTxnDetailsDTO;
import io.mosip.resident.dto.EuinRequestDTO;
import io.mosip.resident.dto.NotificationRequestDto;
import io.mosip.resident.dto.NotificationResponseDTO;
import io.mosip.resident.dto.RegProcRePrintRequestDto;
import io.mosip.resident.dto.RegProcRePrintResponseDto;
import io.mosip.resident.dto.RegStatusCheckResponseDTO;
import io.mosip.resident.dto.RegistrationStatusRequestDTO;
import io.mosip.resident.dto.RegistrationStatusResponseDTO;
import io.mosip.resident.dto.RegistrationStatusSubRequestDto;
import io.mosip.resident.dto.RequestDTO;
import io.mosip.resident.dto.RequestWrapper;
import io.mosip.resident.dto.ResidentReprintRequestDto;
import io.mosip.resident.dto.ResidentReprintResponseDto;
import io.mosip.resident.dto.ResponseDTO;
import io.mosip.resident.dto.ResponseWrapper;
import io.mosip.resident.exception.ApisResourceAccessException;
import io.mosip.resident.exception.OtpValidationFailedException;
import io.mosip.resident.exception.RIDInvalidException;
import io.mosip.resident.exception.ResidentServiceCheckedException;
import io.mosip.resident.exception.ResidentServiceException;
import io.mosip.resident.service.IdAuthService;
import io.mosip.resident.service.NotificationService;
import io.mosip.resident.service.ResidentService;
import io.mosip.resident.util.JsonUtil;
import io.mosip.resident.util.ResidentServiceRestClient;
import io.mosip.resident.util.TokenGenerator;
import io.mosip.resident.util.UINCardDownloadService;

@Service
public class ResidentServiceImpl implements ResidentService {

	private static final String DATETIME_PATTERN = "mosip.utc-datetime-pattern";
	private static final String STATUS_CHECK_ID = "mosip.resident.service.status.check.id";
	private static final String STATUS_CHECEK_VERSION = "mosip.resident.service.status.check.version";
	private static final String REJECTED_MESSAGE = "REJECTED - PLEASE VISIT THE NEAREST CENTER FOR DETAILS.";
	private static final String REREGISTER_MESSAGE = "FAILED - PLEASE VISIT THE NEAREST CENTER FOR DETAILS.";
	private static final String RESEND_MESSAGE = "UNDER PROCESSING - PLEASE CHECK BACK AGAIN LATER.";
	private static final String PROCESSING_MESSAGE = "UNDER PROCESSING - PLEASE CHECK BACK AGAIN LATER.";

	private static final Logger logger = LoggerConfiguration.logConfig(ResidentServiceImpl.class);

	@Autowired
	private UINCardDownloadService uinCardDownloadService;

	@Autowired
	private IdAuthService idAuthService;

	@Autowired
	NotificationService notificationService;

	@Autowired
	private TokenGenerator tokenGenerator;

	@Autowired
	private ResidentServiceRestClient residentServiceRestClient;

	@Autowired
	private RidValidator<String> ridValidator;

	@Autowired
	Environment env;

	@Value("${resident.center.id}")
	private String centerId;

	@Value("${resident.machine.id}")
	private String machineId;

	@Override
	public RegStatusCheckResponseDTO getRidStatus(RequestDTO request) {
		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::getRidStatus():: entry");
		if (!ridValidator.validateId(request.getIndividualId()))
			throw new ResidentServiceException(ResidentErrorCode.INVALID_RID_EXCEPTION.getErrorCode(),
					ResidentErrorCode.INVALID_RID_EXCEPTION.getErrorMessage());
		RegStatusCheckResponseDTO response = null;
		RegistrationStatusResponseDTO responseWrapper = null;
		RegistrationStatusRequestDTO dto = new RegistrationStatusRequestDTO();
		List<RegistrationStatusSubRequestDto> rids = new ArrayList<RegistrationStatusSubRequestDto>();
		RegistrationStatusSubRequestDto rid = new RegistrationStatusSubRequestDto(request.getIndividualId());

		rids.add(rid);
		dto.setRequest(rids);
		dto.setId(env.getProperty(STATUS_CHECK_ID));
		dto.setVersion(env.getProperty(STATUS_CHECEK_VERSION));
		dto.setRequesttime(DateUtils.getUTCCurrentDateTimeString(env.getProperty(DATETIME_PATTERN)));

		try {
			responseWrapper = (RegistrationStatusResponseDTO) residentServiceRestClient.postApi(
					env.getProperty(ApiName.REGISTRATIONSTATUSSEARCH.name()), MediaType.APPLICATION_JSON, dto,
					RegistrationStatusResponseDTO.class, tokenGenerator.getAdminToken());
			if (responseWrapper == null) {
				logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(), "In valid response from Registration status API");
				throw new RIDInvalidException(ResidentErrorCode.IN_VALID_API_RESPONSE.getErrorCode(),
						ResidentErrorCode.IN_VALID_API_RESPONSE.getErrorMessage()
								+ ApiName.REGISTRATIONSTATUSSEARCH.name());
			}

			if (responseWrapper.getErrors() != null && !responseWrapper.getErrors().isEmpty()) {
				logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(), responseWrapper.getErrors().get(0).toString());
				throw new RIDInvalidException(ResidentErrorCode.NO_RID_FOUND_EXCEPTION.getErrorCode(),
						ResidentErrorCode.NO_RID_FOUND_EXCEPTION.getErrorMessage()
								+ responseWrapper.getErrors().get(0).toString());
			}
			if (responseWrapper.getResponse() == null) {
				logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(), "In valid response from Registration status API");
				throw new RIDInvalidException(ResidentErrorCode.IN_VALID_API_RESPONSE.getErrorCode(),
						ResidentErrorCode.IN_VALID_API_RESPONSE.getErrorMessage() + ApiName.REGISTRATIONSTATUSSEARCH);
			}

			String status = validateResponse(responseWrapper.getResponse().getStatusCode());
			response = new RegStatusCheckResponseDTO();
			response.setRidStatus(status);

		} catch (IOException e) {
			throw new ResidentServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		} catch (ApisResourceAccessException e) {
			if (e.getCause() instanceof HttpClientErrorException) {
				HttpClientErrorException httpClientException = (HttpClientErrorException) e.getCause();
				throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						httpClientException.getResponseBodyAsString());

			} else if (e.getCause() instanceof HttpServerErrorException) {
				HttpServerErrorException httpServerException = (HttpServerErrorException) e.getCause();
				throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						httpServerException.getResponseBodyAsString());
			} else {
				throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage() + e.getMessage(), e);
			}

		}

		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::getRidStatus():: exit");
		return response;
	}

	private String validateResponse(String statusCode) {
		if (statusCode.equalsIgnoreCase(RegistrationExternalStatusCode.PROCESSED.name()))
			return statusCode.toUpperCase();
		if (statusCode.equalsIgnoreCase(RegistrationExternalStatusCode.REJECTED.name()))
			return REJECTED_MESSAGE;
		if (statusCode.equalsIgnoreCase(RegistrationExternalStatusCode.REREGISTER.name()))
			return REREGISTER_MESSAGE;
		if (statusCode.equalsIgnoreCase(RegistrationExternalStatusCode.RESEND.name()))
			return RESEND_MESSAGE;
		if (statusCode.equalsIgnoreCase(RegistrationExternalStatusCode.PROCESSING.name()))
			return PROCESSING_MESSAGE;
		return PROCESSING_MESSAGE;

	}

	@Override
	public byte[] reqEuin(EuinRequestDTO dto) throws ResidentServiceCheckedException {
		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::reqEuin():: entry");
		byte[] response = null;

		try {
			if (idAuthService.validateOtp(dto.getTransactionID(), dto.getIndividualId(),
					dto.getIndividualIdType().name(), dto.getOtp())) {

				response = uinCardDownloadService.getUINCard(dto.getIndividualId(), dto.getCardType(),
						dto.getIndividualIdType());
				if (response != null) {
					sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
							NotificationTemplateCode.RS_DOW_UIN_SUCCESS, null);

				} else {
					sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
							NotificationTemplateCode.RS_DOW_UIN_FAILURE, null);
					throw new ResidentServiceException(ResidentErrorCode.REQUEST_FAILED.getErrorCode(),
							ResidentErrorCode.REQUEST_FAILED.getErrorMessage());
				}
			} else {
				logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
				sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
						NotificationTemplateCode.RS_DOW_UIN_FAILURE, null);
				throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
			}
		} catch (ApisResourceAccessException e) {
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_DOW_UIN_FAILURE, null);
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode()
							+ ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorMessage(), e);
		} catch (ResidentServiceCheckedException e) {
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.NOTIFICATION_FAILURE.getErrorCode()
							+ ResidentErrorCode.NOTIFICATION_FAILURE.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			throw new ResidentServiceException(ResidentErrorCode.NOTIFICATION_FAILURE.getErrorCode(),
					ResidentErrorCode.NOTIFICATION_FAILURE.getErrorMessage(), e);
		} catch (OtpValidationFailedException e) {
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode()
							+ ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_DOW_UIN_FAILURE, null);
			throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage(), e);
		}

		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::reqEuin():: exit");
		return response;
	}

	@Override
	public ResidentReprintResponseDto reqPrintUin(ResidentReprintRequestDto dto)
			throws ResidentServiceCheckedException {
		ResidentReprintResponseDto reprintResponse = new ResidentReprintResponseDto();

		try {
			if (!idAuthService.validateOtp(dto.getTransactionID(), dto.getIndividualId(),
					dto.getIndividualIdType().name(), dto.getOtp())) {
				sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
						NotificationTemplateCode.RS_UIN_RPR_FAILURE, null);
				throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
			}
			RegProcRePrintRequestDto rePrintReq = new RegProcRePrintRequestDto();
			rePrintReq.setCardType(dto.getIndividualIdType().name());
			rePrintReq.setCenterId(centerId);
			rePrintReq.setMachineId(machineId);
			rePrintReq.setId(dto.getIndividualId());
			rePrintReq.setIdType(dto.getIndividualIdType().name());
			rePrintReq.setReason("resident");
			rePrintReq.setRegistrationType("RES_REPRINT");
			RequestWrapper<RegProcRePrintRequestDto> request = new RequestWrapper<>();
			request.setRequest(rePrintReq);
			request.setId("mosip.uincard.reprint");
			request.setVersion("1.0");
			request.setRequesttime(DateUtils.getUTCCurrentDateTimeString());
			ResponseWrapper<RegProcRePrintResponseDto> response = residentServiceRestClient.postApi(
					env.getProperty(ApiName.REPRINTUIN.name()), MediaType.APPLICATION_JSON, request,
					ResponseWrapper.class, tokenGenerator.getRegprocToken());
			if (response.getErrors() != null && !response.getErrors().isEmpty()) {
				sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
						NotificationTemplateCode.RS_UIN_RPR_FAILURE, null);
				throw new ResidentServiceException(ResidentErrorCode.RE_PRINT_REQUEST_FAILED.getErrorCode(),
						ResidentErrorCode.RE_PRINT_REQUEST_FAILED.getErrorMessage()
								+ (response.getErrors().get(0).toString()));
			}

			RegProcRePrintResponseDto responseDto = JsonUtil
					.readValue(JsonUtil.writeValueAsString(response.getResponse()), RegProcRePrintResponseDto.class);

			Map<String, Object> additionalAttributes = new HashMap<>();
			additionalAttributes.put("RID", responseDto.getRegistrationId());
			NotificationResponseDTO notificationResponseDTO = sendNotification(dto.getIndividualId(),
					dto.getIndividualIdType(), NotificationTemplateCode.RS_UIN_RPR_SUCCESS, additionalAttributes);
			reprintResponse.setRegistrationId(responseDto.getRegistrationId());
			reprintResponse.setMessage(notificationResponseDTO.getMessage());

		} catch (OtpValidationFailedException e) {
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_UIN_RPR_FAILURE, null);
			throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage(), e);
		} catch (ApisResourceAccessException e) {
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_UIN_RPR_FAILURE, null);
			if (e.getCause() instanceof HttpClientErrorException) {
				HttpClientErrorException httpClientException = (HttpClientErrorException) e.getCause();
				throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						httpClientException.getResponseBodyAsString());

			} else if (e.getCause() instanceof HttpServerErrorException) {
				HttpServerErrorException httpServerException = (HttpServerErrorException) e.getCause();
				throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						httpServerException.getResponseBodyAsString());
			} else {
				throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage() + e.getMessage(), e);
			}
		} catch (IOException e) {
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_UIN_RPR_FAILURE, null);
			throw new ResidentServiceException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		} catch (ResidentServiceCheckedException e) {
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_UIN_RPR_FAILURE, null);
			throw new ResidentServiceException(ResidentErrorCode.NOTIFICATION_FAILURE.getErrorCode(),
					ResidentErrorCode.NOTIFICATION_FAILURE.getErrorMessage(), e);
		}

		return reprintResponse;
	}

	@Override
	public ResponseDTO reqAauthTypeStatusUpdate(AuthLockOrUnLockRequestDto dto, AuthTypeStatus authTypeStatus)
			throws ResidentServiceCheckedException {
		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::reqAauthTypeStatusUpdate():: entry");

		ResponseDTO response = new ResponseDTO();
		boolean isTransactionSuccessful = false;
		try {

			if (idAuthService.validateOtp(dto.getTransactionID(), dto.getIndividualId(),
					dto.getIndividualIdType().name(), dto.getOtp())) {

				boolean isAuthTypeStatusUpdated = idAuthService.authTypeStatusUpdate(dto.getIndividualId(),
						dto.getIndividualIdType().name(), dto.getAuthType(), authTypeStatus);
				if (isAuthTypeStatusUpdated) {
					isTransactionSuccessful = true;

				} else {
					throw new ResidentServiceException(ResidentErrorCode.REQUEST_FAILED.getErrorCode(),
							ResidentErrorCode.REQUEST_FAILED.getErrorMessage());
				}
			} else {

				logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
				throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
			}

		} catch (ApisResourceAccessException e) {
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode()
							+ ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorMessage(), e);
		} catch (OtpValidationFailedException e) {
			throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage(), e);
		} finally {
			NotificationTemplateCode templateCode;
			if (authTypeStatus.equals(AuthTypeStatus.LOCK)) {
				templateCode = isTransactionSuccessful ? NotificationTemplateCode.RS_LOCK_AUTH_SUCCESS
						: NotificationTemplateCode.RS_LOCK_AUTH_FAILURE;
			} else {
				templateCode = isTransactionSuccessful ? NotificationTemplateCode.RS_UNLOCK_AUTH_SUCCESS
						: NotificationTemplateCode.RS_UNLOCK_AUTH_FAILURE;
			}

			NotificationResponseDTO notificationResponseDTO = sendNotification(dto.getIndividualId(),
					dto.getIndividualIdType(), templateCode, null);
			if (notificationResponseDTO != null) {
				response.setMessage(notificationResponseDTO.getMessage());
			}
		}
		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::reqAauthTypeStatusUpdate():: exit");
		return response;
	}

	@Override
	public AuthHistoryResponseDTO reqAuthHistory(AuthHistoryRequestDTO dto) throws ResidentServiceCheckedException {
		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::reqAuthHistory():: entry");

		AuthHistoryResponseDTO response = new AuthHistoryResponseDTO();
		try {

			if (idAuthService.validateOtp(dto.getTransactionID(), dto.getIndividualId(),
					dto.getIndividualIdType().name(), dto.getOtp())) {
				List<AuthTxnDetailsDTO> details = idAuthService.getAuthHistoryDetails(dto.getIndividualId(),
						dto.getIndividualIdType().name(), dto.getPageStart(), dto.getPageFetch());
				if (details != null) {
					response.setAuthHistory(details);

					NotificationResponseDTO notificationResponseDTO = sendNotification(dto.getIndividualId(),
							dto.getIndividualIdType(), NotificationTemplateCode.RS_AUTH_HIST_SUCCESS, null);
					response.setMessage(notificationResponseDTO.getMessage());
				} else {
					sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
							NotificationTemplateCode.RS_AUTH_HIST_FAILURE, null);
					throw new ResidentServiceException(ResidentErrorCode.REQUEST_FAILED.getErrorCode(),
							ResidentErrorCode.REQUEST_FAILED.getErrorMessage());
				}
			} else {
				logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
						LoggerFileConstant.APPLICATIONID.toString(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
				sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
						NotificationTemplateCode.RS_AUTH_HIST_FAILURE, null);
				throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
						ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage());
			}

		} catch (OtpValidationFailedException e) {
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode()
							+ ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_AUTH_HIST_FAILURE, null);
			throw new ResidentServiceException(ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorCode(),
					ResidentErrorCode.OTP_VALIDATION_FAILED.getErrorMessage(), e);
		} catch (ResidentServiceCheckedException e) {
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.NOTIFICATION_FAILURE.getErrorCode()
							+ ResidentErrorCode.NOTIFICATION_FAILURE.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			throw new ResidentServiceException(ResidentErrorCode.NOTIFICATION_FAILURE.getErrorCode(),
					ResidentErrorCode.NOTIFICATION_FAILURE.getErrorMessage(), e);
		} catch (ApisResourceAccessException e) {
			logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
					LoggerFileConstant.APPLICATIONID.toString(),
					ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode()
							+ ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorMessage()
							+ ExceptionUtils.getStackTrace(e));
			sendNotification(dto.getIndividualId(), dto.getIndividualIdType(),
					NotificationTemplateCode.RS_AUTH_HIST_FAILURE, null);
			throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorMessage(), e);
		}
		logger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.APPLICATIONID.toString(),
				LoggerFileConstant.APPLICATIONID.toString(), "ResidentServiceImpl::reqAuthHistory():: exit");
		return response;
	}

	private NotificationResponseDTO sendNotification(String id, IdType idType,
			NotificationTemplateCode templateTypeCode, Map<String, Object> additionalAttributes)
			throws ResidentServiceCheckedException {
		NotificationRequestDto notificationRequest = new NotificationRequestDto(id, idType, templateTypeCode,
				additionalAttributes);
		return notificationService.sendNotification(notificationRequest);
	}

}