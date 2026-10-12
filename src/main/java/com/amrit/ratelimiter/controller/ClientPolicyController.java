package com.amrit.ratelimiter.controller;
import com.amrit.ratelimiter.service.ClientPolicyService;
import com.amrit.ratelimiter.model.ApiClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/admin/clients")
public class ClientPolicyController {
    private final ClientPolicyService service;
    public ClientPolicyController(ClientPolicyService service){this.service=service;}
    @GetMapping public List<ApiClient> list(){return service.list();}
    @PostMapping public ClientPolicyService.Created create(@RequestBody ClientPolicyService.PolicyInput input){return service.create(input);}
    @PutMapping("/{id}") public ApiClient update(@PathVariable String id,@RequestBody ClientPolicyService.PolicyInput input){return service.update(id,input);}
    @PostMapping("/{id}/rotate") public ClientPolicyService.Created rotate(@PathVariable String id){return service.rotate(id);}
}
