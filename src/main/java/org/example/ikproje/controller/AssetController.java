package org.example.ikproje.controller;

import lombok.RequiredArgsConstructor;
import org.example.ikproje.dto.request.NewAssetRequestDto;
import org.example.ikproje.dto.response.BaseResponse;
import org.example.ikproje.service.AssetService;
import org.example.ikproje.view.VwAsset;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.example.ikproje.constant.RestApis.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(ASSET)
@CrossOrigin("*")
public class AssetController {
    private final AssetService assetService;


    //personel üzerine atanan assetleri görüyor
    @GetMapping(GET_PERSONEL_ASSETS)
    @PreAuthorize("hasAnyAuthority('COMPANY_MANAGER','EMPLOYEE')")
    public ResponseEntity<BaseResponse<List<VwAsset>>> getPersonelAssets(@RequestParam String token) {
        return ResponseEntity.ok(BaseResponse.<List<VwAsset>>builder()
                        .code(200)
                .success(true)
                .message("Şirket personellerinin zimmet listesi")
                .data(assetService.getAllPersonelAssets(token))
                .build());
    }


    //Şirket yöneticisi için, bütün personel assetlerini görüyor
    @GetMapping(GET_ASSETS_OF_COMPANY)
    @PreAuthorize("hasAuthority('COMPANY_MANAGER')")
    public ResponseEntity<BaseResponse<List<VwAsset>>> getAssetListOfCompany(@RequestParam String token) {
        return ResponseEntity.ok(BaseResponse.<List<VwAsset>>builder()
                        .code(200)
                .success(true)
                .message("Personel zimmet listesi")
                .data(assetService.getAssetListOfCompany(token))
                .build());
    }

    @PostMapping(ASSIGN_NEW_ASSET)
    @PreAuthorize("hasAuthority('COMPANY_MANAGER')")
    public ResponseEntity<BaseResponse<Boolean>> assignNewAsset(@RequestBody NewAssetRequestDto dto){
        return ResponseEntity.ok(BaseResponse.<Boolean>builder()
                        .message("İşlem başarılı")
                        .success(assetService.assignNewAssetToPersonel(dto))
                        .code(200)
                .build());
    }

    @PutMapping(APPROVE_ASSET)
    @PreAuthorize("hasAuthority('EMPLOYEE')")
    public ResponseEntity<BaseResponse<Boolean>> approveAsset(@RequestParam String token,@RequestParam Long assetId){
        return ResponseEntity.ok(BaseResponse.<Boolean>builder()
                .message("Zimmet atanması onaylandı.")
                .success(assetService.approveAssetAssignment(token,assetId))
                .code(200)
                .build());
    }

    @PutMapping(REJECT_ASSET)
    @PreAuthorize("hasAuthority('EMPLOYEE')")
    public ResponseEntity<BaseResponse<Boolean>> rejectAsset(@RequestParam String token,@RequestParam Long assetId,String rejectMessage){
        return ResponseEntity.ok(BaseResponse.<Boolean>builder()
                .message("Zimmet atanması reddedildi.")
                .success(assetService.rejectAssetAssignment(token,assetId,rejectMessage))
                .code(200)
                .build());
    }


}