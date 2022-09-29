package com.amondfarm.api.domain;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;

import com.amondfarm.api.domain.enums.user.Gender;
import com.amondfarm.api.domain.enums.user.ProviderType;
import com.amondfarm.api.domain.enums.user.RoleType;
import com.amondfarm.api.domain.enums.user.UserStatus;
import com.amondfarm.api.dto.CreateUserDto;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@Column(name = "user_id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 소셜로그인 Provider
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProviderType providerType;

	// 소셜로그인 id
	@Column(nullable = false)
	private String loginId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserStatus status;

	private String reasonForWithdraw;

	private String loginUsername;

	private Gender gender;

	private String email;

	private String inviteCode;

	@Enumerated(EnumType.STRING)
	private RoleType roleType;

	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
	private List<UserMission> userMissions = new ArrayList<>();

	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
	private List<UserCharacter> userCharacters = new ArrayList<>();

	//==연관관계 method==//
	public void addUserMission(UserMission userMission) {
		userMissions.add(userMission);
		userMission.setUser(this);
	}

	// TODO 연관관계 Set
	public void addUserCharacter(UserCharacter userCharacter) {
		userCharacters.add(userCharacter);
		userCharacter.setUser(this);
	}

	/**
	 * 생성 method
	 * @param userDto 회원가입 시 유저 정보와 2일치 미션 정보 Set
	 * @return
	 */
	// TODO User 생성 시 UserCharacter add 하는 로직 추가
	public static User from(CreateUserDto userDto) {
		User user = User.builder()
			.providerType(userDto.getProviderType())
			.loginId(userDto.getLoginId())
			.loginUsername(userDto.getLoginUsername())
			.build();

		for (UserMission usermission : userDto.getUserMissions()) {
			user.addUserMission(usermission);
		}

		user.addUserCharacter(userDto.getUserCharacter());

		return user;
	}

	@Builder
	public User(ProviderType providerType, String loginId, String loginUsername,
		Gender gender, String email) {

		this.providerType = providerType;
		this.loginId = loginId;
		this.loginUsername = loginUsername;
		this.gender = gender;
		this.email = email;
		this.status = UserStatus.ACTIVE;
		this.roleType = RoleType.USER;
	}

	//==비즈니스 로직==//
	public void changeStatus(UserStatus status) {
		this.status = status;
	}

	public boolean isActivate() {
		return this.status.equals(UserStatus.ACTIVE);
	}
}
