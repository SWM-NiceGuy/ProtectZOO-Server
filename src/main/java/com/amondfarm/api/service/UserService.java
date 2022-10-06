package com.amondfarm.api.service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.amondfarm.api.domain.Mission;
import com.amondfarm.api.domain.MissionExampleImage;
import com.amondfarm.api.domain.Pet;
import com.amondfarm.api.domain.PetLevelValue;
import com.amondfarm.api.domain.User;
import com.amondfarm.api.domain.UserMission;
import com.amondfarm.api.domain.UserPet;
import com.amondfarm.api.domain.enums.mission.MissionType;
import com.amondfarm.api.domain.enums.pet.AcquisitionCondition;
import com.amondfarm.api.dto.CreateUserDto;
import com.amondfarm.api.dto.MissionDto;
import com.amondfarm.api.dto.MissionHistory;
import com.amondfarm.api.dto.SlackDoMissionDto;
import com.amondfarm.api.dto.request.ChangePetNicknameRequest;
import com.amondfarm.api.dto.request.DeviceToken;
import com.amondfarm.api.dto.response.ChangePetNicknameResponse;
import com.amondfarm.api.dto.response.DailyMissionsResponse;
import com.amondfarm.api.dto.response.InitPetResponse;
import com.amondfarm.api.dto.response.MissionHistoryResponse;
import com.amondfarm.api.dto.response.UserMissionDetailResponse;
import com.amondfarm.api.repository.MissionRepository;
import com.amondfarm.api.repository.PetLevelRepository;
import com.amondfarm.api.repository.PetRepository;
import com.amondfarm.api.repository.UserMissionRepository;
import com.amondfarm.api.repository.UserRepository;
import com.amondfarm.api.security.dto.UserInfoResponse;
import com.amondfarm.api.security.util.SecurityUtil;
import com.amondfarm.api.util.S3Uploader;
import com.amondfarm.api.util.SlackService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class UserService {

	@Value(value = "${server-address.cdnUrl}")
	private String cdnUrl;

	private final UserRepository userRepository;
	private final UserMissionRepository userMissionRepository;
	private final MissionRepository missionRepository;
	private final PetRepository petRepository;
	private final PetLevelRepository petLevelRepository;

	private final S3Uploader s3Uploader;

	private final SlackService slackService;

	public User getCurrentUser() {
		return SecurityUtil.getCurrentUsername()
			.flatMap(id -> userRepository.findById(Long.valueOf(id)))
			.orElseThrow(() -> new NoSuchElementException("해당 회원이 없습니다."));
	}

	// 현재 유저의 캐릭터 정보 조회
	public InitPetResponse getUserPetInfo() {

		// 획득조건이 BETA 인 유저펫 리턴
		UserPet userPet = getCurrentUser().getUserPets().stream()
			.filter(up -> up.getPet().getAcquisitionCondition() == AcquisitionCondition.BETA)
			.findFirst().orElseThrow(() -> new NoSuchElementException("캐릭터가 없습니다."));

		PetLevelValue petLevelValue = petLevelRepository.findByLevel(userPet.getCurrentLevel())
			.orElseThrow(() -> new NoSuchElementException("해당 레벨의 정보가 없습니다."));

		return InitPetResponse.builder()
			.petId(userPet.getId())
			.image(getPetStageImage(userPet))
			.name(userPet.getPet().getPetName())
			.nickname(userPet.getNickname())
			.currentLevel(userPet.getCurrentLevel())
			.currentExp(userPet.getCurrentExp())
			.maxExp(petLevelValue.getMaxExp())
			.build();
	}

	// 현재 UserPet 의 단계에 따라 이미지를 리턴하는 함수
	// TODO Fetch JOIN 으로 전체 펫 테이블을 가져와서 실루엣 이미지도 채워서 반환하기
	private String getPetStageImage(UserPet userPet) {

		Pet pet = userPet.getPet();
		switch (userPet.getCurrentStage()) {
			case 1:
				return pet.getStage1ImageUrl();
			case 2:
				return pet.getStage2ImageUrl();
			case 3:
				return pet.getStage3ImageUrl();
			case 4:
				return pet.getStage4ImageUrl();
		}
		return null;
	}

	@Transactional
	public ChangePetNicknameResponse setUserPetNickname(ChangePetNicknameRequest changePetNicknameRequest) {

		UserPet userPet = getCurrentUser().getUserPets().stream()
			.filter(p -> p.getId() == changePetNicknameRequest.getUserPetId())
			.findFirst().orElseThrow(() -> new NoSuchElementException("해당 아이디의 캐릭터가 없습니다."));

		userPet.changeNickname(changePetNicknameRequest.getNickname());

		return ChangePetNicknameResponse.builder()
			.userPetId(userPet.getId())
			.nickname(userPet.getNickname())
			.build();
	}

	public User joinUser(UserInfoResponse userInfoResponse) {

		// 데일리 미션 찾기
		List<Mission> dailyMissions = missionRepository.findAllMissionsByMissionType(MissionType.DAILY);
		List<UserMission> userMissions = new ArrayList<>();
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime tomorrow = now.plusDays(1);

		for (Mission mission : dailyMissions) {
			// 오늘 날짜의 미션 삽입
			userMissions.add(new UserMission(mission, now));
			// 다음 날짜의 미션 미리 삽입
			userMissions.add(new UserMission(mission, tomorrow));
		}

		// BETA 캐릭터 찾기
		Pet pet = petRepository.findByAcquisitionCondition(AcquisitionCondition.BETA)
			.orElseThrow(() -> new NoSuchElementException("BETA 캐릭터가 없습니다."));

		// UserPet 생성
		UserPet userPet = UserPet.builder()
			.pet(pet)
			.build();

		CreateUserDto userDto = CreateUserDto.builder()
			.loginId(userInfoResponse.getLoginId())
			.providerType(userInfoResponse.getProviderType())
			.loginUsername(userInfoResponse.getNickname())
			.email(userInfoResponse.getEmail())
			.userMissions(userMissions)
			.userPet(userPet)
			.build();

		return userRepository.save(User.from(userDto));
	}

	// 현재 날짜에 해당하는 유저만의 데일리 미션을 조회
	public DailyMissionsResponse getDailyMissions() {
		User user = getCurrentUser();
		List<MissionDto> missions = new ArrayList<>();

		List<UserMission> userMissions = user.getUserMissions().stream()
			.filter(m -> LocalDate.now().isEqual(m.getActivatedAt().toLocalDate()))
			.collect(Collectors.toList());

		for (UserMission userMission : userMissions) {
			missions.add(MissionDto.builder()
				.id(userMission.getId())
				.name(userMission.getMission().getTitle())
				.iconUrl(userMission.getMission().getIconUrl())
				.state(userMission.getMissionStatus())
				.reward(userMission.getMission().getReward())
				.build());
		}

		return DailyMissionsResponse.builder()
			.totalMissions(missions.size())
			.missions(missions)
			.build();
	}

	public UserMissionDetailResponse getUserMissionDetail(Long userMissionId) {
		UserMission userMission = userMissionRepository.findById(userMissionId)
			.orElseThrow(() -> new NoSuchElementException("잘못된 미션 조회입니다."));

		List<String> exampleImageUrls = new ArrayList<>();
		for (MissionExampleImage exampleImage : userMission.getMission().getExampleImages()) {
			exampleImageUrls.add(exampleImage.getImageUrl());
		}

		return UserMissionDetailResponse.builder()
			.name(userMission.getMission().getTitle())
			.description(userMission.getMission().getDescription())
			.content(userMission.getMission().getContent())
			.submitGuide(userMission.getMission().getSubmitGuide())
			.exampleImageUrls(exampleImageUrls)
			.rewardType(userMission.getMission().getRewardType())
			.reward(userMission.getMission().getReward())
			.state(userMission.getMissionStatus())
			.build();
	}

	// user 가 미션 수행 시
	// 1. S3 업로드
	// user_mission 테이블 update
	// 1. 미션 사진 url submissionImageUrl 에 저장
	// 2. 수행시각 현재 시각으로 업로드
	// 3. 미션 상태 WAIT 으로 변경
	// slack 전송
	@Transactional
	public void doMission(Long userMissionId, MultipartFile submissionImage) {

		User currentUser = getCurrentUser();

		log.info("user id : " + currentUser.getId());

		try {
			String uploadImageUrl = cdnUrl + s3Uploader.upload(submissionImage, currentUser.getId().toString());

			UserMission userMission = currentUser.getUserMissions().stream()
				.filter(um -> um.getId() == userMissionId)
				.findFirst().orElseThrow(() -> new NoSuchElementException("잘못된 미션 ID 입니다."));

			userMission.doMission(uploadImageUrl, LocalDateTime.now());

			// slack 전송
			slackService.postSlack(
				SlackDoMissionDto.builder()
					.userId(currentUser.getId())
					.loginUsername(currentUser.getLoginUsername())
					.accomplishedAt(userMission.getAccomplishedAt())
					.userMissionId(userMissionId)
					.missionName(userMission.getMission().getTitle())
					.missionImageUrl(uploadImageUrl)
					.build()
			);

		} catch (IOException e) {
			e.printStackTrace();
			log.info("S3 업로드에 실패했습니다.");
		}
	}

	@Transactional
	public void setDeviceToken(DeviceToken request) {
		getCurrentUser().changeDeviceToken(request.getDeviceToken());
	}


	public MissionHistoryResponse getMissionHistory() {
		List<UserMission> userMissions = getCurrentUser().getUserMissions();

		List<UserMission> triedMissions = userMissions.stream()
			.filter(um -> um.isMissionTried())
			.collect(Collectors.toList());

		List<MissionHistory> missionHistories = new ArrayList<>();
		for (UserMission triedMission : triedMissions) {
			missionHistories.add(MissionHistory.builder()
				.missionHistoryId(triedMission.getId())
				.date(Timestamp.valueOf(triedMission.getAccomplishedAt()).getTime())
				.title(triedMission.getMission().getTitle())
				.state(triedMission.getMissionStatus())
				.rewardType(triedMission.getMission().getRewardType())
				.reward(triedMission.getMission().getReward())
				.build()
			);
		}

		return MissionHistoryResponse.builder()
			.totalMissionHistory(missionHistories.size())
			.missionHistories(missionHistories)
			.build();
	}
}