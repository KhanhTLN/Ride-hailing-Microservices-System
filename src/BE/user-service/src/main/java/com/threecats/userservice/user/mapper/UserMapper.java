package com.threecats.userservice.user.mapper;

import com.threecats.common.config.CentralMapperConfig;
import com.threecats.userservice.user.dto.request.UpdateUserProfileRequest;
import com.threecats.userservice.user.entity.User;
import org.hibernate.sql.Update;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(config = CentralMapperConfig.class)
public interface UserMapper {
    void updateUserFromDto(UpdateUserProfileRequest dto, @MappingTarget User user);
}
