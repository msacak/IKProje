package org.example.ikproje.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.ikproje.dto.request.LoginRequestDto;
import org.example.ikproje.dto.request.RegisterRequestDto;
import org.example.ikproje.dto.request.ResetPasswordRequestDto;
import org.example.ikproje.dto.response.UserProfileResponseDto;
import org.example.ikproje.entity.*;
import org.example.ikproje.entity.enums.EState;
import org.example.ikproje.entity.enums.EUserRole;
import org.example.ikproje.exception.ErrorType;
import org.example.ikproje.exception.IKProjeException;
//import org.example.ikproje.mapper.AddressMapper;
import org.example.ikproje.mapper.*;
import org.example.ikproje.repository.UserRepository;
import org.example.ikproje.utility.EncryptionManager;
import org.example.ikproje.utility.JwtManager;
import org.example.ikproje.view.VwCompanyManager;
import org.example.ikproje.view.VwPersonel;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
	private final UserRepository userRepository;
	private final CompanyService companyService;
	private final UserDetailsService userDetailsService;
	private final AddressService addressService;
	private final JwtManager jwtManager;
	private final EmailService emailService;
	private final VerificationTokenService verificationTokenService;
	private final CloudinaryService cloudinaryService;
	private final AssetService assetService;
	private final MembershipService membershipService;

	@Transactional
	public void register(RegisterRequestDto dto) {
		if (userRepository.existsByEmail(dto.companyEmail())){
			throw new IKProjeException(ErrorType.MAIL_ALREADY_EXIST);
		}
		User user = UserMapper.INSTANCE.fromRegisterDto(dto);
		user.setUserRole(EUserRole.COMPANY_MANAGER);
		String encryptedPassword = EncryptionManager.getEncryptedPassword(dto.companyPassword());
		user.setEmail(dto.companyEmail());
		user.setPassword(encryptedPassword);
		user.setState(EState.PASSIVE);
		Address userAddress = AddressMapper.INSTANCE.toUserAddress(dto);
		Address companyAddress = AddressMapper.INSTANCE.toCompanyAddress(dto);
		addressService.saveAll(Arrays.asList(userAddress, companyAddress));
		UserDetails userDetails = UserDetailsMapper.INSTANCE.fromRegisterDto(dto);
		userDetails.setAddressId(userAddress.getId());
		Company company = CompanyMapper.INSTANCE.fromRegisterDto(dto);
		company.setAddressId(companyAddress.getId());
		company.setPassword(encryptedPassword);
		companyService.save(company);
		user.setCompanyId(company.getId());
		user.setState(EState.ACTIVE);
		userRepository.save(user);
		userDetails.setUserId(user.getId());
		userDetailsService.save(userDetails);
		Membership membership = MembershipMapper.INSTANCE.fromRegisterDto(dto);
		membership.setCompanyId(company.getId());
		membership.setPrice(membership.getMembershipType().getDiscountRate()*membership.getPrice());
		membership.setStartDate(membership.getMembershipType().getStartDate());
		membership.setEndDate(membership.getMembershipType().getEndDate());
		membershipService.save(membership);
		//Mail gönderirken alıcı olarak dto'dan gelen (şirket yöneticisi) mail adresi ve oluşturduğum verification
		// token'i giriyorum
		emailService.sendEmail(dto.companyEmail(),verificationTokenService.generateVerificationToken(user.getId()));
	}
	
	
	/**
	 * Bu method'a parametre olarak gelen token, veritabanında var mı diye kontrol ediliyor.
	 * Daha sonra token'in süresi dolmuş mu diye kontrol ediliyor.
	 * Daha sonra token'in içerisinden companyId alınıp, company'nin varlığı kontrol ediliyor.
	 * Eğer tüm şartlar sağlanıyorsa mail onayı gerçekleşiyor ve token'in state'i passive oluyor.
	 * @param verificationToken
	 */
	public void verifyAccount(String verificationToken){
		Optional<VerificationToken> optVerificationToken = verificationTokenService.findByToken(verificationToken);
		if (optVerificationToken.isEmpty()){
			throw new IKProjeException(ErrorType.INVALID_TOKEN);
		}
		VerificationToken verToken = optVerificationToken.get();
		if (verToken.getState()==EState.PASSIVE){
			throw new IKProjeException(ErrorType.TOKEN_ALREADY_USED);
		}
		long expDate = Long.parseLong(verificationToken.substring(16));
		if (expDate<System.currentTimeMillis()){
			verToken.setState(EState.PASSIVE);
			verificationTokenService.save(verToken);
			throw new IKProjeException(ErrorType.EXP_TOKEN);
		}
		Long companyId = verToken.getCompanyId();
		Optional<Company> optCompany = companyService.findById(companyId);
		if (optCompany.isEmpty()){
			throw new IKProjeException(ErrorType.COMPANY_NOTFOUND);
		}
		Company company = optCompany.get();
		company.setIsMailVerified(true);
		verToken.setState(EState.PASSIVE);
		companyService.save(company);
		verificationTokenService.save(verToken);
	}
	
	public String login(LoginRequestDto dto) {
		String encryptedPassword = EncryptionManager.getEncryptedPassword(dto.password());
		Optional<Company> optCompany =
				companyService.findOptionalByEmailAndPassword(dto.email(), encryptedPassword);
		if (optCompany.isEmpty()){
			//Personel girişi için userRepository mail ve pw kontrol edilmeli o yüzden geçici olarak eklendi.
			Optional<User> personelOptional = userRepository.findOptionalByEmailAndPassword(dto.email(), encryptedPassword);
			if(personelOptional.isPresent()){
				return jwtManager.createUserToken(personelOptional.get().getId());
			}
			throw new IKProjeException(ErrorType.INVALID_USERNAME_OR_PASSWORD);
		}
		Company company = optCompany.get();
		if(!company.getIsMailVerified()){
			emailService.sendEmail(dto.email(),verificationTokenService.generateVerificationToken(company.getId()));
			throw new IKProjeException(ErrorType.MAIL_NOT_VERIFIED);
		}
		return jwtManager.createUserToken(company.getId());
	}


	public UserProfileResponseDto getProfile(String token) {
		Optional<Long> optionalUserId = jwtManager.validateToken(token);
		if(optionalUserId.isEmpty()){
			throw new IKProjeException(ErrorType.INVALID_TOKEN);
		}
		Optional<User> optionalUser = userRepository.findById(optionalUserId.get());
		if (optionalUser.isEmpty()){
			throw new IKProjeException(ErrorType.USER_NOTFOUND);
		}
		User user = optionalUser.get();

		UserProfileResponseDto dto = UserProfileResponseDto.builder()
				.firstName(optionalUser.get().getFirstName())
				.lastName(optionalUser.get().getLastName())
				.avatarUrl(optionalUser.get().getAvatarUrl())
				.build();
		return dto;
	}
	
	public void addLogoToCompany(String token, Long companyId, MultipartFile file) throws IOException {
		System.out.println(companyId);
		Optional<Long> optUserId = jwtManager.validateToken(token);
		if (optUserId.isEmpty()){
			throw new IKProjeException(ErrorType.INVALID_TOKEN);
		}
		Optional<User> optUser = userRepository.findById(optUserId.get());
		if (optUser.isEmpty()){
			throw new IKProjeException(ErrorType.USER_NOTFOUND);
		}
		User user = optUser.get();
		if (!user.getUserRole().equals(EUserRole.COMPANY_MANAGER)){
			throw new IKProjeException(ErrorType.UNAUTHORIZED);
		}
		Optional<Company> optCompany = companyService.findById(companyId);
		if (optCompany.isEmpty()){
			throw new IKProjeException(ErrorType.COMPANY_NOTFOUND);
		}
		Company company = optCompany.get();
		company.setLogo(cloudinaryService.uploadFile(file));
		companyService.save(company);
	}
	
	public void addAvatarToUser(String token,  MultipartFile file) throws IOException {
		Optional<Long> optUserId = jwtManager.validateToken(token);
		if (optUserId.isEmpty()){
			throw new IKProjeException(ErrorType.INVALID_TOKEN);
		}
		Optional<User> optUser = userRepository.findById(optUserId.get());
		if (optUser.isEmpty()){
			throw new IKProjeException(ErrorType.USER_NOTFOUND);
		}
		User user = optUser.get();
		if (!user.getUserRole().equals(EUserRole.COMPANY_MANAGER)){
			throw new IKProjeException(ErrorType.UNAUTHORIZED);
		}
		user.setAvatarUrl(cloudinaryService.uploadFile(file));
		userRepository.save(user);
	}

	public VwPersonel getPersonelProfile(String token){
		Optional<Long> userIdOpt = jwtManager.validateToken(token);
		if (userIdOpt.isEmpty()) throw new IKProjeException(ErrorType.INVALID_TOKEN);
		Optional<VwPersonel> vwPersonelOptional = userRepository.findVwPersonelByUserId(userIdOpt.get());
		if (vwPersonelOptional.isEmpty()) throw new IKProjeException(ErrorType.USER_NOTFOUND);
		VwPersonel vwPersonel = vwPersonelOptional.get();
		vwPersonel.setAssets(assetService.getAllVwAssetsByUserId(vwPersonel.getId()));
		return vwPersonel;
	}

	public VwCompanyManager getCompanyManagerProfile(String token){
		Optional<Long> userIdOpt = jwtManager.validateToken(token);
		if (userIdOpt.isEmpty()) throw new IKProjeException(ErrorType.INVALID_TOKEN);
		Optional<VwCompanyManager> vwCompanyManagerOptional = userRepository.findVwCompanyManagerByUserId(userIdOpt.get());
		if(vwCompanyManagerOptional.isEmpty()) throw new IKProjeException(ErrorType.USER_NOTFOUND);
		VwCompanyManager vwCompanyManager = vwCompanyManagerOptional.get();
		vwCompanyManager.setPersonelList(userRepository.findAllVwPersonelByCompanyId(vwCompanyManager.getCompanyId(),EUserRole.EMPLOYEE.toString()));
		return vwCompanyManager;
	}

	public Boolean forgotPassword(String email) {
		Optional<User> userOptional = userRepository.findByEmail(email);
		if (userOptional.isEmpty()) throw new IKProjeException(ErrorType.MAIL_NOT_FOUND);
		User user = userOptional.get();
		String token = jwtManager.createResetPasswordToken(user.getId(),email);
		emailService.sendResetPasswordEmail(email,token);
		return true;
	}

	public Boolean resetPassword(ResetPasswordRequestDto dto) {
		Optional<Long> userIdOpt = jwtManager.validateToken(dto.token());
		if (userIdOpt.isEmpty()) throw new IKProjeException(ErrorType.INVALID_TOKEN);
		Optional<User> optUser = userRepository.findById(userIdOpt.get());
		if (optUser.isEmpty()) throw new IKProjeException(ErrorType.USER_NOTFOUND);
		User user = optUser.get();
		if(!dto.password().equals(dto.rePassword())) throw new IKProjeException(ErrorType.PASSWORDS_NOT_MATCH);

		if(user.getUserRole().equals(EUserRole.COMPANY_MANAGER)){
			Company company = companyService.findById(user.getCompanyId()).get();
			company.setPassword(EncryptionManager.getEncryptedPassword(dto.password()));
			companyService.save(company);
			return true;
		}
		user.setPassword(EncryptionManager.getEncryptedPassword(dto.password()));
		userRepository.save(user);
		return true;
	}
}